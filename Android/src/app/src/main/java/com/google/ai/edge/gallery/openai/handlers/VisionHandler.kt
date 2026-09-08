/*
 * I4a: POST /v1/vision/detect, POST /v1/vision/segment (MediaPipe tasks-vision, D10: GPU
 * delegate with CPU fallback, no raw .tflite/QNN stack), and POST /v1/images/edits (D12: honest
 * 501 -- SD-based image editing is not served over HTTP on this device, see handleImageEdits).
 *
 * Model loading here is NOT cached across requests: each call creates a fresh
 * ObjectDetectorWrapper/ImageSegmenterWrapper, runs one detect()/segment(), and closes it before
 * responding, all under a single per-model Mutex (so there is never more than one load+infer in
 * flight for a given model, and no concurrent request can observe a wrapper mid-close). This
 * trades a bit of per-request latency (reloading the .tflite from disk) for not having to reason
 * about a long-lived shared instance's lifecycle across unrelated requests/app-lifecycle events
 * -- the wrapper classes' own lock discipline (see their file headers) still holds even if a
 * future revision starts caching instances, which is the point of putting it there rather than
 * here.
 *
 * Image decoding reuses parseMessageContent from MultimodalContent.kt (base64 data-URI decode,
 * 10MB cap, http(s) URL rejection) by wrapping the single `image` field into the same
 * content-parts shape chat completions already accepts, rather than writing a second decoder.
 */
package com.google.ai.edge.gallery.openai.handlers

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.ai.edge.gallery.openai.BoundingBoxData
import com.google.ai.edge.gallery.openai.DetectionData
import com.google.ai.edge.gallery.openai.ErrorBody
import com.google.ai.edge.gallery.openai.ErrorEnvelope
import com.google.ai.edge.gallery.openai.SegmentCategoryData
import com.google.ai.edge.gallery.openai.VisionDetectRequest
import com.google.ai.edge.gallery.openai.VisionDetectResponse
import com.google.ai.edge.gallery.openai.VisionSegmentRequest
import com.google.ai.edge.gallery.openai.VisionSegmentResponse
import com.google.ai.edge.gallery.relay.vision.ModelCatalog
import com.google.ai.edge.gallery.relay.vision.ObjectDetectorWrapper
import com.google.ai.edge.gallery.relay.vision.ImageSegmenterWrapper
import com.google.ai.edge.gallery.relay.vision.VisionModelNotDownloadedException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// These are single-image, tens-of-milliseconds MediaPipe operations (not the minutes-long SD
// path) -- 60s is generous headroom for a cold model load off disk plus one inference.
private const val VISION_TIMEOUT_MS = 60 * 1000L

private const val DEFAULT_MAX_RESULTS = 10
private const val DEFAULT_SCORE_THRESHOLD = 0.3f

/** Wraps a single base64 data-URI into the content-parts shape parseMessageContent() accepts. */
private fun imageUriToContentParts(uri: String): JsonElement = buildJsonArray {
    add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject { put("url", uri) })
    })
}

/** Decodes `image` via the shared MultimodalContent decoder; null means "already responded". */
private suspend fun decodeImageOrRespondError(call: ApplicationCall, image: String): Bitmap? {
    if (image.isBlank()) {
        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Missing required 'image' field")))
        return null
    }
    when (val parsed = parseMessageContent(imageUriToContentParts(image))) {
        is ContentParseResult.Error -> {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = parsed.message)))
            return null
        }
        is ContentParseResult.Ok -> {
            val bitmap = parsed.parsed.images.firstOrNull()
            if (bitmap == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Could not decode 'image'")))
                return null
            }
            return bitmap
        }
    }
}

suspend fun handleVisionDetect(
    call: ApplicationCall,
    context: Context,
    mutexes: ConcurrentHashMap<String, Mutex>,
) {
    val request = call.receive<VisionDetectRequest>()
    val bitmap = decodeImageOrRespondError(call, request.image) ?: return

    val maxResults = request.max_results ?: DEFAULT_MAX_RESULTS
    if (maxResults <= 0) {
        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "'max_results' must be positive")))
        return
    }
    val scoreThreshold = request.score_threshold ?: DEFAULT_SCORE_THRESHOLD
    if (scoreThreshold < 0f || scoreThreshold > 1f) {
        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "'score_threshold' must be in [0, 1]")))
        return
    }

    val modelName = request.model ?: ModelCatalog.DEFAULT_DETECTOR_MODEL
    val modelFile = ModelCatalog.detectorFile(context, request.model)
    if (!modelFile.exists()) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorEnvelope(ErrorBody(message = ModelCatalog.detectorNotDownloadedMessage(context)))
        )
        return
    }

    try {
        // No cancel available: ObjectDetectorWrapper doesn't expose an async abort, and the
        // wrapper is created and closed entirely within block -- its own `finally { wrapper.close() }`
        // already runs on any exception/cancellation, so there is nothing beyond that (already
        // brief, tens-of-milliseconds) window left to orphan. onCancel is intentionally {}.
        val guardResult = withBusyGuard(
            mutexes = mutexes,
            key = modelName,
            timeoutMs = VISION_TIMEOUT_MS,
        ) {
            withContext(Dispatchers.Default) {
                val wrapper = ObjectDetectorWrapper.create(
                    context = context,
                    modelFile = modelFile,
                    expectedDownloadMessage = ModelCatalog.detectorNotDownloadedMessage(context),
                    maxResults = maxResults,
                    scoreThreshold = scoreThreshold,
                )
                val detections = try {
                    wrapper.detect(bitmap, maxResults, scoreThreshold)
                } finally {
                    wrapper.close()
                }
                call.respond(
                    VisionDetectResponse(
                        detections = detections.map {
                            DetectionData(
                                label = it.label,
                                score = it.score,
                                box = BoundingBoxData(it.box.x, it.box.y, it.box.width, it.box.height),
                            )
                        }
                    )
                )
            }
        }
        when (guardResult) {
            is BusyResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, ErrorEnvelope(ErrorBody(message = "Model '$modelName' is busy")))
            is BusyResult.TimedOut -> call.respond(
                HttpStatusCode.GatewayTimeout,
                ErrorEnvelope(ErrorBody(message = "Object detection exceeded the ${VISION_TIMEOUT_MS / 1000}s timeout"))
            )
            is BusyResult.Ok -> {}
        }
    } catch (e: VisionModelNotDownloadedException) {
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorEnvelope(ErrorBody(message = e.message ?: "Model not downloaded")))
    }
}

suspend fun handleVisionSegment(
    call: ApplicationCall,
    context: Context,
    mutexes: ConcurrentHashMap<String, Mutex>,
) {
    val request = call.receive<VisionSegmentRequest>()
    val bitmap = decodeImageOrRespondError(call, request.image) ?: return

    val modelName = request.model ?: ModelCatalog.DEFAULT_SEGMENTER_MODEL
    val modelFile = ModelCatalog.segmenterFile(context, request.model)
    if (!modelFile.exists()) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorEnvelope(ErrorBody(message = ModelCatalog.segmenterNotDownloadedMessage(context)))
        )
        return
    }

    try {
        // No cancel available -- same reasoning as handleVisionDetect above.
        val guardResult = withBusyGuard(
            mutexes = mutexes,
            key = modelName,
            timeoutMs = VISION_TIMEOUT_MS,
        ) {
            withContext(Dispatchers.Default) {
                val wrapper = ImageSegmenterWrapper.create(
                    context = context,
                    modelFile = modelFile,
                    expectedDownloadMessage = ModelCatalog.segmenterNotDownloadedMessage(context),
                    categories = ModelCatalog.segmenterCategories(request.model),
                )
                val result = try {
                    wrapper.segment(bitmap)
                } finally {
                    wrapper.close()
                }

                // Encode the category-index mask as a visually-grayscale PNG (R=G=B=value,
                // alpha=255). Android's Bitmap PNG encoder doesn't expose an 8-bit
                // single-channel (color type 0) PNG output directly, so this is ARGB_8888
                // with equal channels rather than a true grayscale-color-type PNG -- any PNG
                // decoder reads the per-pixel category value the same way either way.
                val w = result.categoryMaskWidth
                val h = result.categoryMaskHeight
                val pixels = IntArray(w * h)
                for (i in pixels.indices) {
                    val v = result.categoryMaskBytes[i].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                val maskBitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                val stream = ByteArrayOutputStream()
                maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                call.respond(
                    VisionSegmentResponse(
                        category_mask_png_b64 = b64,
                        categories = result.categories.map { SegmentCategoryData(it.value, it.label) },
                    )
                )
            }
        }
        when (guardResult) {
            is BusyResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, ErrorEnvelope(ErrorBody(message = "Model '$modelName' is busy")))
            is BusyResult.TimedOut -> call.respond(
                HttpStatusCode.GatewayTimeout,
                ErrorEnvelope(ErrorBody(message = "Image segmentation exceeded the ${VISION_TIMEOUT_MS / 1000}s timeout"))
            )
            is BusyResult.Ok -> {}
        }
    } catch (e: VisionModelNotDownloadedException) {
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorEnvelope(ErrorBody(message = e.message ?: "Model not downloaded")))
    }
}

/*
 * D12 (locked): honest 501, not an implementation. SD-based image editing exists in the app's
 * own UI but is CPU-only at roughly 15-20 minutes per image on this device (measured: ~55s per
 * sampler step plus a fixed ~83s VAE decode -- same cost profile as ImageGenerationHandler.kt's
 * /v1/images/generations path), so it is not served over HTTP here; use the app UI instead.
 *
 * POST /v1/images/edits is OpenAI's standard image-edit route (image + mask + prompt ->
 * inpainted image). If SD generation is ever accelerated on this device (D10 explicitly defers
 * that -- no QNN/raw-.tflite stack in this card), this is where a real implementation belongs,
 * mirroring handleImageGenerations' per-model Mutex/timeout/cancel-on-timeout structure.
 */
suspend fun handleImageEdits(call: ApplicationCall) {
    call.respond(
        HttpStatusCode.NotImplemented,
        ErrorEnvelope(ErrorBody(
            message = "Image editing is not served over HTTP on this device. Stable Diffusion " +
                "image editing exists in the app UI but is CPU-only (~15-20 minutes per image: " +
                "~55s/sampler step plus a fixed ~83s VAE decode), so it is not exposed via this " +
                "API. Use the app UI for image edits.",
            type = "not_implemented",
        ))
    )
}
