package com.google.ai.edge.gallery.whisper

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.Default) {
        if (contextHandle != 0L) freeModel()
        contextHandle = loadModelNative(modelPath)
        contextHandle != 0L
    }

    suspend fun transcribe(audioData: FloatArray, language: String = "en"): String =
        withContext(Dispatchers.Default) {
            if (contextHandle == 0L) return@withContext ""
            transcribeNative(contextHandle, audioData, language)
        }

    fun freeModel() {
        if (contextHandle != 0L) {
            freeModelNative(contextHandle)
            contextHandle = 0L
        }
    }

    val isLoaded get() = contextHandle != 0L

    private external fun loadModelNative(modelPath: String): Long
    private external fun transcribeNative(handle: Long, audioData: FloatArray, language: String): String
    private external fun freeModelNative(handle: Long)
}

/** Records 16kHz mono PCM16 from the mic and returns it as float32 samples. */
fun recordAudio(durationMs: Int = 30_000, onStopped: () -> FloatArray): FloatArray {
    val bufferSize = AudioRecord.getMinBufferSize(
        WhisperEngine.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(4096)

    val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        WhisperEngine.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize,
    )

    val pcmBuffer = mutableListOf<Short>()
    val chunk = ShortArray(bufferSize / 2)

    recorder.startRecording()
    val deadline = System.currentTimeMillis() + durationMs
    while (System.currentTimeMillis() < deadline) {
        val read = recorder.read(chunk, 0, chunk.size)
        if (read > 0) {
            for (i in 0 until read) pcmBuffer.add(chunk[i])
        }
    }
    recorder.stop()
    recorder.release()

    return pcmBuffer.map { it / 32768f }.toFloatArray()
}

/** Convert a ShortArray of PCM16 samples to float32. */
fun ShortArray.toFloat32(): FloatArray = FloatArray(size) { this[it] / 32768f }
