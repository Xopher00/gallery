/*
 * I4a: plain (non-serialization) result types returned by the vision/ wrapper classes. Kept
 * separate from openai/OpenAiModels.kt's @Serializable DTOs on purpose -- these are the internal
 * MediaPipe-facing shapes; VisionHandler.kt maps them onto the wire format.
 */
package com.google.ai.edge.gallery.vision

/** Pixel-space box; origin (0,0) is the top-left corner of the input bitmap. */
data class VisionBoundingBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class VisionDetection(
    val label: String,
    val score: Float,
    val box: VisionBoundingBox,
)

/** One label associated with a pixel value in a category mask. */
data class VisionMaskCategory(val value: Int, val label: String)

data class VisionSegmentationResult(
    /** Single-channel (one byte per pixel) category mask, same width/height as the input. */
    val categoryMaskWidth: Int,
    val categoryMaskHeight: Int,
    val categoryMaskBytes: ByteArray,
    val categories: List<VisionMaskCategory>,
)

/** Thrown by a wrapper's detect()/segment() when called after close(). */
class VisionModelClosedException(message: String) : IllegalStateException(message)

/** Thrown when the expected .task model file isn't present on disk yet. */
class VisionModelNotDownloadedException(message: String) : IllegalStateException(message)
