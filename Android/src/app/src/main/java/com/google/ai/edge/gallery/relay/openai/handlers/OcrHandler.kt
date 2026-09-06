/*
 * I4b: POST /v1/vision/ocr -- on-device text recognition via bundled ML Kit (vision/OcrEngine.kt,
 * D11: no Play Services required at runtime). Unlike VisionHandler.kt's detect()/segment(), there
 * is NO 503 "model not downloaded" case here: the recognizer model is statically linked into the
 * APK, so it is ALWAYS available regardless of network/download state.
 *
 * Same per-request lifecycle as VisionHandler.kt's handleVisionDetect/handleVisionSegment: a
 * fresh OcrEngine is created, used once, and closed in a finally block, all under a single-key
 * Mutex (there is only one recognizer type here, so the OpenAiServer.kt-owned mutex map is keyed
 * uniformly rather than per-model like the vision detect/segment maps -- `model` is accepted and
 * IGNORED per the task's "accept and ignore it for client convenience" instruction, since there
 * is only one recognizer to route to).
 *
 * Image decoding reuses parseMessageContent from MultimodalContent.kt (base64 data-URI decode,
 * 10MB cap, http(s) URL rejection), same as VisionHandler.kt, rather than writing a second
 * decoder.
 */
package com.google.ai.edge.gallery.relay.openai.handlers

import com.google.ai.edge.gallery.relay.openai.ErrorBody
import com.google.ai.edge.gallery.relay.openai.ErrorEnvelope
import com.google.ai.edge.gallery.relay.openai.OcrBlockData
import com.google.ai.edge.gallery.relay.openai.OcrBoundingBoxData
import com.google.ai.edge.gallery.relay.openai.OcrRequest
import com.google.ai.edge.gallery.relay.openai.OcrResponse
import com.google.ai.edge.gallery.relay.vision.OcrEngine
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wraps a single base64 data-URI into the content-parts shape parseMessageContent() accepts --
 *  same trick VisionHandler.kt's (private, so not reusable across files) imageUriToContentParts
 *  uses, duplicated here rather than made public there since VisionHandler.kt is off-limits for
 *  this card. */
private fun imageUriToOcrContentParts(uri: String): JsonElement = buildJsonArray {
    add(buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject { put("url", uri) })
    })
}

// OCR is a single-image, sub-second on-device operation (not the minutes-long SD path) -- 60s is
// generous headroom, matching VisionHandler.kt's VISION_TIMEOUT_MS for the same class of op.
private const val OCR_TIMEOUT_MS = 60 * 1000L

// Only one recognizer type exists, so the busy-guard mutex map (keyed like the vision
// detect/segment maps in OpenAiServer.kt for consistency) uses one fixed key rather than one per
// request-supplied `model` value -- `model` is accepted and ignored (see file header).
private const val OCR_MUTEX_KEY = "ocr"

suspend fun handleOcr(
    call: ApplicationCall,
    mutexes: ConcurrentHashMap<String, Mutex>,
) {
    val request = call.receive<OcrRequest>()

    if (request.image.isBlank()) {
        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Missing required 'image' field")))
        return
    }
    val bitmap = when (val parsed = parseMessageContent(imageUriToOcrContentParts(request.image))) {
        is ContentParseResult.Error -> {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = parsed.message)))
            return
        }
        is ContentParseResult.Ok -> {
            val decoded = parsed.parsed.images.firstOrNull()
            if (decoded == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Could not decode 'image'")))
                return
            }
            decoded
        }
    }

    // No cancel available: OcrEngine doesn't expose an async abort, and the engine is created and
    // closed entirely within block -- its own `finally { engine.close() }` already runs on any
    // exception/cancellation, so onCancel is intentionally {}.
    val guardResult = withBusyGuard(
        mutexes = mutexes,
        key = OCR_MUTEX_KEY,
        timeoutMs = OCR_TIMEOUT_MS,
    ) {
        withContext(Dispatchers.Default) {
            val engine = OcrEngine.create()
            val result = try {
                engine.recognize(bitmap)
            } finally {
                engine.close()
            }
            call.respond(
                OcrResponse(
                    text = result.text,
                    blocks = result.blocks.map {
                        OcrBlockData(
                            text = it.text,
                            box = OcrBoundingBoxData(it.box.x, it.box.y, it.box.width, it.box.height),
                            confidence = it.confidence,
                        )
                    },
                )
            )
        }
    }
    when (guardResult) {
        is BusyResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, ErrorEnvelope(ErrorBody(message = "OCR is busy with another request")))
        is BusyResult.TimedOut -> call.respond(
            HttpStatusCode.GatewayTimeout,
            ErrorEnvelope(ErrorBody(message = "OCR exceeded the ${OCR_TIMEOUT_MS / 1000}s timeout"))
        )
        is BusyResult.Ok -> {}
    }
}
