/*
 * I4a: thin wrapper around MediaPipe tasks-vision's ObjectDetector. GPU delegate first (D10: no
 * raw .tflite/QNN stack in this card), falling back to CPU if GPU delegate init throws --
 * matches the "GPU with CPU fallback" instruction and the fact that GPU delegate availability
 * varies by device/driver.
 *
 * Lifecycle discipline (same class of bug as SDInference.cpp's g_sd_mutex / free_sd_ctx race,
 * three prior SIGSEGVs in this project): close() must never run while detect() is executing on
 * another thread, because ObjectDetector.close() tears down the native TFLite interpreter that
 * detect() may still be reading from mid-call. detectLock (a plain, non-reentrant
 * ReentrantLock -- detect()/close() are synchronous blocking calls, not suspend functions, so a
 * coroutine Mutex isn't the right primitive here) is held for the full duration of BOTH detect()
 * and close(), and `closed` is only ever flipped true while holding it. That serializes the two
 * operations: a close() that arrives mid-detect() blocks on the lock until detect() returns, and
 * a detect() that arrives after close() sees `closed == true` and throws before touching the
 * (already-freed) native object -- it can never observe a half-torn-down detector.
 */
package com.google.ai.edge.gallery.relay.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ObjectDetectorWrapper private constructor(
    private var detector: ObjectDetector,
    val usedGpuDelegate: Boolean,
) {
    private val detectLock = ReentrantLock()
    @Volatile private var closed = false

    fun detect(bitmap: Bitmap, maxResults: Int, scoreThreshold: Float): List<VisionDetection> {
        detectLock.withLock {
            if (closed) throw VisionModelClosedException("ObjectDetectorWrapper used after close()")

            // maxResults/scoreThreshold are set at options-build time in MediaPipe's API, not
            // per-call -- rebuilding per request would reload the model, so instead just detect
            // with whatever the instance was built with and post-filter/truncate here. The
            // instance is (re)built with the caller's first-seen values by companion.create();
            // a later request with different values still gets a correct (if not
            // options-optimal) result via this filter.
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = detector.detect(mpImage)
            return result.detections()
                .asSequence()
                .filter { d -> d.categories().firstOrNull()?.score()?.let { it >= scoreThreshold } ?: false }
                .map { d -> d.toVisionDetection() }
                .take(maxResults)
                .toList()
        }
    }

    fun close() {
        detectLock.withLock {
            if (closed) return
            closed = true
            detector.close()
        }
    }

    companion object {
        /**
         * Loads [modelFile] with the GPU delegate, retrying once with the CPU delegate if GPU
         * init throws. Throws [VisionModelNotDownloadedException] if the file doesn't exist yet
         * (see vision/ModelCatalog.kt) rather than letting MediaPipe's own IO error surface.
         */
        fun create(
            context: Context,
            modelFile: File,
            expectedDownloadMessage: String,
            maxResults: Int,
            scoreThreshold: Float,
        ): ObjectDetectorWrapper {
            if (!modelFile.exists()) {
                throw VisionModelNotDownloadedException(expectedDownloadMessage)
            }

            fun build(delegate: Delegate): ObjectDetector {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(delegate)
                    .build()
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setMaxResults(maxResults)
                    .setScoreThreshold(scoreThreshold)
                    .build()
                return ObjectDetector.createFromOptions(context, options)
            }

            return try {
                ObjectDetectorWrapper(build(Delegate.GPU), usedGpuDelegate = true)
            } catch (e: Exception) {
                ObjectDetectorWrapper(build(Delegate.CPU), usedGpuDelegate = false)
            }
        }
    }
}

private fun com.google.mediapipe.tasks.components.containers.Detection.toVisionDetection(): VisionDetection {
    val category = categories().first()
    val box: RectF = boundingBox()
    return VisionDetection(
        label = category.categoryName() ?: "class_${category.index()}",
        score = category.score(),
        box = VisionBoundingBox(
            x = box.left,
            y = box.top,
            width = box.width(),
            height = box.height(),
        ),
    )
}
