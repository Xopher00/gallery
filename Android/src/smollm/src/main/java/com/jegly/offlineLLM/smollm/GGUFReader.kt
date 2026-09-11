package com.jegly.offlineLLM.smollm

import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GGUFReader : Closeable {
    companion object {
        init {
            System.loadLibrary("ggufreader")
        }
    }

    private var nativeHandle: Long = 0L

    fun open(modelPath: String) {
        nativeHandle = getGGUFContextNativeHandle(modelPath)
    }

    suspend fun load(modelPath: String) = withContext(Dispatchers.IO) { open(modelPath) }

    fun getContextSize(): Long? {
        check(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val contextSize = getContextSize(nativeHandle)
        return if (contextSize == -1L) null else contextSize
    }

    fun getChatTemplate(): String? {
        check(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val chatTemplate = getChatTemplate(nativeHandle)
        return chatTemplate.ifEmpty { null }
    }

    fun getPoolingType(): Long? {
        check(nativeHandle != 0L) { "Use GGUFReader.load() to initialize the reader" }
        val poolingType = getPoolingType(nativeHandle)
        return if (poolingType == -1L) null else poolingType
    }

    // Idempotent: safe to call more than once, including via a second `use {}` on the same reader.
    override fun close() {
        if (nativeHandle != 0L) {
            releaseGGUFContext(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun getGGUFContextNativeHandle(modelPath: String): Long
    private external fun getContextSize(nativeHandle: Long): Long
    private external fun getChatTemplate(nativeHandle: Long): String
    private external fun getPoolingType(nativeHandle: Long): Long
    private external fun releaseGGUFContext(nativeHandle: Long)
}
