// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.huggingface.DeviceHardwareInfo
import com.google.ai.edge.gallery.huggingface.DeviceVendor
import com.google.ai.edge.gallery.huggingface.isLiteRtLmFileName

/** A resolved value plus whether it came from the filename instead of the file header. */
data class FileDetail<T>(val value: T, val fromFilename: Boolean)

/** Resolved import details; `null` means the file says nothing and today's defaults stand. */
data class ModelFileDetails(
    val contextLength: FileDetail<Int>? = null,
    val accelerator: FileDetail<Accelerator>? = null,
)

private const val EKV_TOKEN_PREFIX = "ekv"
private const val GPU_TOKEN = "gpu"
private const val GPU_BACKEND_CONSTRAINT = "tf_lite_artisan_text_decoder"

// ggufDeclaredContextLength comes from the caller since GGUFReader is native; caller also applies the engine cap.
fun resolveModelFileDetails(
    fileName: String,
    litertlmHeaderPath: String,
    ggufDeclaredContextLength: Long? = null,
): ModelFileDetails =
    when {
        fileName.endsWith(".gguf", ignoreCase = true) ->
            ModelFileDetails(
                contextLength =
                    ggufDeclaredContextLength?.let { FileDetail(it.toInt(), fromFilename = false) },
                accelerator = null,
            )
        isLiteRtLmFileName(fileName) -> {
            val headerInfo = probeLiteRtLmBackendInfo(litertlmHeaderPath)
            ModelFileDetails(
                contextLength = liteRtLmContextLength(fileName),
                accelerator =
                    liteRtLmAcceleratorFromHeader(headerInfo) ?: liteRtLmAcceleratorFromFilename(fileName),
            )
        }
        else -> ModelFileDetails()
    }

private fun litertlmTokens(fileName: String): Set<String> =
    fileName.lowercase().removeSuffix(".litertlm").split('_', '-', '.', ' ', '/').toSet()

internal fun liteRtLmContextLength(fileName: String): FileDetail<Int>? {
    val ekvToken =
        litertlmTokens(fileName).firstOrNull {
            it.startsWith(EKV_TOKEN_PREFIX) &&
                it.length > EKV_TOKEN_PREFIX.length &&
                it.substring(EKV_TOKEN_PREFIX.length).all(Char::isDigit)
        } ?: return null
    return FileDetail(ekvToken.substring(EKV_TOKEN_PREFIX.length).toInt(), fromFilename = true)
}

internal fun liteRtLmAcceleratorFromHeader(info: LiteRtLmBackendInfo): FileDetail<Accelerator>? =
    if (
        info.hasGpuArtisanWeightsVersionKey ||
            info.backendConstraints.any { it.equals(GPU_BACKEND_CONSTRAINT, ignoreCase = true) }
    ) {
        FileDetail(Accelerator.GPU, fromFilename = false)
    } else {
        null
    }

internal fun liteRtLmAcceleratorFromFilename(fileName: String): FileDetail<Accelerator>? {
    val genericDevice = DeviceHardwareInfo(DeviceVendor.GENERIC_ANDROID)
    return when {
        !genericDevice.isCompatibleWithFile(fileName) -> FileDetail(Accelerator.NPU, fromFilename = true)
        GPU_TOKEN in litertlmTokens(fileName) -> FileDetail(Accelerator.GPU, fromFilename = true)
        else -> null
    }
}
