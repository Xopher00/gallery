package com.google.ai.edge.gallery.whisper

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WhisperEngine {

    companion object {
        const val SAMPLE_RATE = 16000

        init {
            System.loadLibrary("whisper_jni")
        }
    }

    private var contextHandle: Long = 0L
    private val stateLock = ReentrantLock()

    @Volatile
    var isTranscribing = false
        private set

    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.Default) {
        if (contextHandle != 0L) freeModel()
        contextHandle = loadModelNative(modelPath)
        contextHandle != 0L
    }

    suspend fun transcribe(audioData: FloatArray, language: String = "en"): String =
        withContext(Dispatchers.Default) {
            if (contextHandle == 0L) return@withContext ""
            stateLock.withLock { isTranscribing = true }
            try {
                val result = transcribeNative(contextHandle, audioData, language)
                // transcribeNative returns an empty string both on cancellation and on
                // genuine silence; empty is treated as "no transcript" either way, not an error.
                result
            } finally {
                stateLock.withLock { isTranscribing = false }
            }
        }

    /**
     * Frees the native whisper context.
     *
     * Blocks on the native mutex until any in-flight transcription returns; never call from
     * the Main thread.
     */
    fun freeModel() {
        // Capture the handle and zero the field while still holding the lock, then free the
        // captured local outside the (already-released) lock. This makes a second concurrent or
        // subsequent freeModel() call see contextHandle == 0L and no-op instead of racing to
        // free the same native pointer twice (use-after-free / double-free).
        val handle = stateLock.withLock {
            val h = contextHandle
            contextHandle = 0L
            h
        }
        if (handle != 0L) {
            freeModelNative(handle)
        }
    }

    /** Requests cancellation of any in-flight transcription via whisper.cpp's abort_callback. */
    fun cancelTranscription() {
        cancelTranscriptionNative()
    }

    val isLoaded get() = contextHandle != 0L

    private external fun loadModelNative(modelPath: String): Long
    private external fun transcribeNative(handle: Long, audioData: FloatArray, language: String): String
    private external fun freeModelNative(handle: Long)
    private external fun cancelTranscriptionNative()
}

/** Convert a ShortArray of PCM16 samples to float32. */
fun ShortArray.toFloat32(): FloatArray = FloatArray(size) { this[it] / 32768f }
