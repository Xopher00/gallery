/*
 * I4a: the fixed set of MediaPipe .task models this server knows how to load. D10 (locked): v1
 * is tasks-vision only, no download flow is wired up in this card -- VisionHandler.kt checks
 * File.exists() on the expected path and returns 503 naming this exact file/URL when it's
 * missing, rather than fetching it itself (see file header comment there for what a real
 * download flow would need).
 *
 * Files are expected under {context.getExternalFilesDir(null)}/vision_models/<fileName> --
 * app-external storage, same tier Model.kt already uses for downloaded model weights (see
 * Model.kt's localFileRelativeDirPathOverride/download-path comments), so a user (or a future
 * download-flow implementation) can `adb push` a file straight to a predictable, inspectable
 * path without touching app-private storage.
 */
package com.google.ai.edge.gallery.vision

import android.content.Context
import java.io.File

private const val VISION_MODELS_SUBDIR = "vision_models"

object ModelCatalog {
    // First-party MediaPipe model host (per D10/step 2 -- storage.googleapis.com/mediapipe-models,
    // NOT bundled in the APK, NOT a Box model file). Default detector: EfficientDet-Lite0
    // (COCO 80-class labels come from the model's own embedded TFLite metadata via
    // Detection.categories()/categoryName() -- no hardcoded label list needed for detection).
    const val DEFAULT_DETECTOR_MODEL = "efficientdet_lite0"
    private val DETECTOR_FILE_NAME = "efficientdet_lite0.tflite"
    // Verified 2026-09-02 against the published MediaPipe Object Detector model index
    // (developers.google.com/edge/mediapipe/solutions/vision/object_detector): the doc's
    // canonical URL uses "float32/latest/", not the versioned "float32/1/" this card previously
    // guessed. Both resolve (HTTP 200) today, but "latest" is what MediaPipe's own docs list.
    private const val DETECTOR_URL =
        "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/latest/efficientdet_lite0.tflite"

    // Default segmenter: DeepLab v3 (PASCAL VOC 21-class label set below -- MediaPipe's Java
    // ImageSegmenter API has no reliable getLabels() across versions, so this is hardcoded
    // against this exact model rather than read at runtime; see ImageSegmenterWrapper.kt header).
    const val DEFAULT_SEGMENTER_MODEL = "deeplab_v3"
    private val SEGMENTER_FILE_NAME = "deeplab_v3.tflite"
    // Verified 2026-09-02 against the published MediaPipe Image Segmenter model index
    // (developers.google.com/edge/mediapipe/solutions/vision/image_segmenter): the doc's
    // canonical URL uses "float32/latest/", not the versioned "float32/1/" this card previously
    // guessed. Both resolve (HTTP 200) today, but "latest" is what MediaPipe's own docs list.
    private const val SEGMENTER_URL =
        "https://storage.googleapis.com/mediapipe-models/image_segmenter/deeplab_v3/float32/latest/deeplab_v3.tflite"

    private val DEEPLAB_V3_LABELS = listOf(
        "background", "aeroplane", "bicycle", "bird", "boat", "bottle", "bus", "car", "cat",
        "chair", "cow", "diningtable", "dog", "horse", "motorbike", "person", "pottedplant",
        "sheep", "sofa", "train", "tvmonitor",
    ).mapIndexed { index, label -> VisionMaskCategory(value = index, label = label) }

    private fun visionModelsDir(context: Context): File =
        File(context.getExternalFilesDir(null), VISION_MODELS_SUBDIR)

    fun detectorFile(context: Context, requestedModel: String?): File {
        // Only one detector is catalogued in this card; `requestedModel` is accepted (matches
        // the request shape's optional `model` field) but any value maps to the same file for
        // now -- there is nothing else to route to.
        return File(visionModelsDir(context), DETECTOR_FILE_NAME)
    }

    fun detectorNotDownloadedMessage(context: Context): String =
        "Object detection model not downloaded. Expected file: " +
            "${visionModelsDir(context)}/$DETECTOR_FILE_NAME " +
            "(download from $DETECTOR_URL and place it at that path; no in-app download flow " +
            "is wired up yet -- see ModelCatalog.kt)"

    fun segmenterFile(context: Context, requestedModel: String?): File =
        File(visionModelsDir(context), SEGMENTER_FILE_NAME)

    fun segmenterCategories(requestedModel: String?): List<VisionMaskCategory> = DEEPLAB_V3_LABELS

    fun segmenterNotDownloadedMessage(context: Context): String =
        "Image segmentation model not downloaded. Expected file: " +
            "${visionModelsDir(context)}/$SEGMENTER_FILE_NAME " +
            "(download from $SEGMENTER_URL and place it at that path; no in-app download flow " +
            "is wired up yet -- see ModelCatalog.kt)"
}
