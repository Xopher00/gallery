// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import com.jegly.offlineLLM.smollm.GGUFReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ModelFileKind { CHAT, EMBEDDING }

// Unreadable or unrecognized files default to CHAT (user decision), not an error.
fun probeModelFileKind(path: String): ModelFileKind =
    try {
        when {
            path.endsWith(".litertlm", ignoreCase = true) -> probeLiteRtLmFileKind(path)
            path.endsWith(".gguf", ignoreCase = true) -> probeGgufFileKind(path)
            else -> ModelFileKind.CHAT
        }
    } catch (e: Exception) {
        ModelFileKind.CHAT
    }

private const val LITERTLM_MAGIC = "LITERTLM"
private const val LITERTLM_HEADER_CAP_BYTES = 64 * 1024
private const val FLATBUFFER_STRING_VALUE_TAG = 9 // KeyValue.value_type union tag for StringValue
private const val TF_LITE_PREFILL_DECODE = "tf_lite_prefill_decode"

private fun probeLiteRtLmFileKind(path: String): ModelFileKind {
    val modelTypes = probeLiteRtLmModelTypes(path)
    return when {
        modelTypes.isEmpty() -> ModelFileKind.CHAT
        // Section names are upper case in NPU-compiled bundles, lower case elsewhere.
        modelTypes.any { it.equals(TF_LITE_PREFILL_DECODE, ignoreCase = true) } -> ModelFileKind.CHAT
        else -> ModelFileKind.EMBEDDING
    }
}

internal fun probeLiteRtLmModelTypes(path: String): List<String> {
    RandomAccessFile(path, "r").use { raf ->
        val prefix = ByteArray(32)
        raf.readFully(prefix)
        if (String(prefix, 0, 8, Charsets.US_ASCII) != LITERTLM_MAGIC) return emptyList()
        val headerEndOffset = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).getLong(24)
        val header = ByteArray(minOf(headerEndOffset, LITERTLM_HEADER_CAP_BYTES.toLong()).toInt())
        raf.seek(0)
        raf.readFully(header)
        return collectLiteRtLmModelTypes(ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN))
    }
}

// A chat bundle can carry tf_lite_embedder too; only presence of tf_lite_prefill_decode means chat.
private fun collectLiteRtLmModelTypes(buf: ByteBuffer): List<String> {
    val rootTable = resolveOffset(buf, 32)
    val sectionsHolder = readOffsetField(buf, rootTable, fieldId = 1) ?: return emptyList()
    val sectionsVec = readOffsetField(buf, sectionsHolder, fieldId = 0) ?: return emptyList()
    val values = mutableListOf<String>()
    for (section in readTableVector(buf, sectionsVec)) {
        val keyValueVec = readOffsetField(buf, section, fieldId = 0) ?: continue
        for (keyValue in readTableVector(buf, keyValueVec)) {
            val keyPos = readOffsetField(buf, keyValue, fieldId = 0) ?: continue
            if (readFbString(buf, keyPos) != "model_type") continue
            if (readUByteField(buf, keyValue, fieldId = 1) != FLATBUFFER_STRING_VALUE_TAG) continue
            val stringValueTable = readOffsetField(buf, keyValue, fieldId = 2) ?: continue
            val stringPos = readOffsetField(buf, stringValueTable, fieldId = 0) ?: continue
            values.add(readFbString(buf, stringPos))
        }
    }
    return values
}

private fun resolveOffset(buf: ByteBuffer, at: Int): Int = at + buf.getInt(at)

// Resolved through the vtable by field id, not a fixed byte offset; a 0 slot means field absent.
private fun vtableFieldPosition(buf: ByteBuffer, tableStart: Int, fieldId: Int): Int? {
    val vtable = tableStart - buf.getInt(tableStart)
    val vtableSize = buf.getShort(vtable).toInt() and 0xFFFF
    val slot = 4 + 2 * fieldId
    if (slot >= vtableSize) return null
    val fieldOffset = buf.getShort(vtable + slot).toInt() and 0xFFFF
    return if (fieldOffset == 0) null else tableStart + fieldOffset
}

private fun readOffsetField(buf: ByteBuffer, tableStart: Int, fieldId: Int): Int? =
    vtableFieldPosition(buf, tableStart, fieldId)?.let { resolveOffset(buf, it) }

private fun readUByteField(buf: ByteBuffer, tableStart: Int, fieldId: Int): Int =
    vtableFieldPosition(buf, tableStart, fieldId)?.let { buf.get(it).toInt() and 0xFF } ?: 0

private fun readTableVector(buf: ByteBuffer, vecPos: Int): List<Int> {
    val count = buf.getInt(vecPos)
    return (0 until count).map { i -> resolveOffset(buf, vecPos + 4 + 4 * i) }
}

private fun readFbString(buf: ByteBuffer, strPos: Int): String {
    val length = buf.getInt(strPos)
    val bytes = ByteArray(length)
    val dup = buf.duplicate()
    dup.position(strPos + 4)
    dup.get(bytes)
    return String(bytes, Charsets.UTF_8)
}

private fun probeGgufFileKind(path: String): ModelFileKind =
    GGUFReader().use { reader ->
        reader.open(path)
        // Matches LLMInference.cpp: pooling_type must be present and non-NONE(0) to mean embedding.
        val poolingType = reader.getPoolingType()
        if (poolingType != null && poolingType != 0L) ModelFileKind.EMBEDDING else ModelFileKind.CHAT
    }
