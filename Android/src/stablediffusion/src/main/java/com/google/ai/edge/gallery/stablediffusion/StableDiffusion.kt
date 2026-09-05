package com.google.ai.edge.gallery.stablediffusion

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StableDiffusion {

    companion object {
        private const val TAG = "StableDiffusion"

        init {
            System.loadLibrary("stablediffusion")
        }
    }

    private var contextHandle: Long = 0L
    private val stateLock = ReentrantLock()

    @Volatile
    var isGenerating = false
        private set

    @Volatile
    private var cancelRequested = false

    data class GenerationParams(
        val prompt: String,
        val negativePrompt: String = "ugly, deformed, blurry, low quality",
        val width: Int = 512,
        val height: Int = 512,
        val steps: Int = 20,
        val cfgScale: Float = 7.5f,
        val seed: Long = -1L,
    )

    data class GenerationProgress(
        val step: Int,
        val totalSteps: Int,
        val bitmap: Bitmap?,
    )

    fun isLoaded(): Boolean = contextHandle != 0L

    suspend fun loadModel(modelPath: String, nThreads: Int = 4): Boolean =
        withContext(Dispatchers.Default) {
            if (contextHandle != 0L) {
                freeContextNative(contextHandle)
                contextHandle = 0L
            }
            contextHandle = loadModelNative(modelPath, nThreads)
            contextHandle != 0L
        }

    fun generateImage(params: GenerationParams): Flow<GenerationProgress> = channelFlow {
        val handle = contextHandle
        if (handle == 0L) {
            Log.e(TAG, "generateImage called without loaded model")
            return@channelFlow
        }

        val seed = if (params.seed < 0) System.currentTimeMillis() else params.seed

        stateLock.withLock {
            isGenerating = true
            cancelRequested = false
        }

        // Progress polling: read atomic counters updated by the C++ progress callback
        val pollJob = launch {
            var lastStep = -1
            while (isActive) {
                delay(150)
                val step = getProgressStep()
                val total = getProgressTotal()
                if (step != lastStep && total > 0 && step > 0) {
                    lastStep = step
                    send(GenerationProgress(step = step, totalSteps = total, bitmap = null))
                }
            }
        }

        try {
            val rawBytes = withContext(Dispatchers.Default) {
                generateImageNative(
                    handle,
                    params.prompt,
                    params.negativePrompt,
                    params.width,
                    params.height,
                    params.steps,
                    params.cfgScale,
                    seed,
                )
            }
            pollJob.cancel()

            if (rawBytes != null) {
                val bitmap = rgbBytesToBitmap(rawBytes, params.width, params.height)
                send(GenerationProgress(
                    step = params.steps,
                    totalSteps = params.steps,
                    bitmap = bitmap,
                ))
            } else {
                // generateImageNative returns null both on cancellation and on genuine failure;
                // the native side already logs which one it was ("generation cancelled" vs
                // "generate_image returned null"), so mirror that distinction here.
                if (cancelRequested) {
                    Log.i(TAG, "Generation cancelled")
                } else {
                    Log.e(TAG, "Generation returned null — model may have failed to load properly")
                }
            }
        } catch (e: Exception) {
            pollJob.cancel()
            Log.e(TAG, "Generation failed", e)
        } finally {
            stateLock.withLock { isGenerating = false }
        }
    }

    /** Requests cancellation of any in-flight [generateImage] call. Native side aborts between
     * ggml graph nodes (not mid-op), so the current node still runs to completion. */
    fun cancelGeneration() {
        cancelRequested = true
        cancelGenerationNative()
    }

    /**
     * Blocks on the native mutex until any in-flight generation returns — may take up to one
     * graph node; never call from the Main thread.
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
            freeContextNative(handle)
        }
    }

    private fun rgbBytesToBitmap(rgb: ByteArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val idx = i * 3
            val r = rgb[idx].toInt() and 0xFF
            val g = rgb[idx + 1].toInt() and 0xFF
            val b = rgb[idx + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private external fun loadModelNative(modelPath: String, nThreads: Int): Long
    private external fun generateImageNative(
        ctxHandle: Long,
        prompt: String, negPrompt: String,
        width: Int, height: Int,
        steps: Int, cfgScale: Float, seed: Long,
    ): ByteArray?
    private external fun getProgressStep(): Int
    private external fun getProgressTotal(): Int
    private external fun cancelGenerationNative()
    private external fun freeContextNative(ctxHandle: Long)
}
