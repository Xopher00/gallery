// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * I4a: thin wrapper around MediaPipe tasks-vision's ImageSegmenter (category mask only --
 * confidence masks aren't requested since the API surface exposed here is a single flat mask +
 * label list, see OpenAiModels.VisionSegmentResponse).
 *
 * Same lock discipline as ObjectDetectorWrapper (see its file header for the full rationale --
 * this is the same class of bug that caused three prior SIGSEGVs in SDInference.cpp /
 * WhisperInference.cpp): segmentLock is held for the full duration of both segment() and
 * close(), and `closed` only flips true under that lock, so close() can never tear down the
 * native segmenter while segment() is still reading from it, and a segment() arriving after
 * close() throws instead of touching freed state.
 *
 * Label lists are NOT read from the model at runtime -- MediaPipe's Java ImageSegmenter API
 * doesn't expose a reliable getLabels() accessor across versions, and this wrapper only ever
 * loads one specific, known model file (see ModelCatalog.kt), so its label set is hardcoded
 * alongside the catalog entry rather than guessed at from the model.
 */
package com.google.ai.edge.gallery.relay.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ImageSegmenterWrapper private constructor(
    private var segmenter: ImageSegmenter,
    private val categories: List<VisionMaskCategory>,
    val usedGpuDelegate: Boolean,
) {
    private val segmentLock = ReentrantLock()
    @Volatile private var closed = false

    fun segment(bitmap: Bitmap): VisionSegmentationResult {
        segmentLock.withLock {
            if (closed) throw VisionModelClosedException("ImageSegmenterWrapper used after close()")

            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = segmenter.segment(mpImage)
            val maskImage = result.categoryMask().orElseThrow {
                IllegalStateException("Segmenter returned no category mask (outputCategoryMask was requested)")
            }
            try {
                val buffer = ByteBufferExtractor.extract(maskImage)
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                return VisionSegmentationResult(
                    categoryMaskWidth = maskImage.width,
                    categoryMaskHeight = maskImage.height,
                    categoryMaskBytes = bytes,
                    categories = categories,
                )
            } finally {
                maskImage.close()
            }
        }
    }

    fun close() {
        segmentLock.withLock {
            if (closed) return
            closed = true
            segmenter.close()
        }
    }

    companion object {
        fun create(
            context: Context,
            modelFile: File,
            expectedDownloadMessage: String,
            categories: List<VisionMaskCategory>,
        ): ImageSegmenterWrapper {
            if (!modelFile.exists()) {
                throw VisionModelNotDownloadedException(expectedDownloadMessage)
            }

            fun build(delegate: Delegate): ImageSegmenter {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(delegate)
                    .build()
                val options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .setOutputCategoryMask(true)
                    .setOutputConfidenceMasks(false)
                    .build()
                return ImageSegmenter.createFromOptions(context, options)
            }

            return try {
                ImageSegmenterWrapper(build(Delegate.GPU), categories, usedGpuDelegate = true)
            } catch (e: Exception) {
                ImageSegmenterWrapper(build(Delegate.CPU), categories, usedGpuDelegate = false)
            }
        }
    }
}
