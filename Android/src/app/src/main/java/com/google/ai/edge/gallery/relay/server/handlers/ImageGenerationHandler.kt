// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * WP F1: POST /v1/images/generations.
 *
 * A single image takes MINUTES on this device (CPU-only Stable Diffusion build: ~55s/step plus
 * a fixed ~85s VAE decode), so this handler deliberately refuses to fan out n>1 and applies a
 * generous but finite request timeout so a hung/slow generation can't tie up a Ktor worker
 * forever. On timeout the handler simply stops waiting and responds; the native generation keeps
 * running to completion in the background.
 */
package com.google.ai.edge.gallery.relay.server.handlers

import android.graphics.Bitmap
import android.util.Base64
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.ImageData
import com.google.ai.edge.gallery.relay.server.LoadResult
import com.google.ai.edge.gallery.relay.server.ImageGenerationRequest
import com.google.ai.edge.gallery.relay.server.ImageGenerationResponse
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.runtime.ModelEngine
import com.google.ai.edge.gallery.stablediffusion.StableDiffusion
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex

// Named per the task card: an image takes minutes here, but a request must not be allowed to
// hang a Ktor worker indefinitely.
private const val IMAGE_GEN_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes

// Matches the just-lowered UI default (ImageGenTaskModule's SD_STEPS_CONFIG.defaultValue = 6f),
// not StableDiffusion.GenerationParams' own default of 20 -- the API has no `steps` parameter in
// the OpenAI shape, so use the same "keeps this device's generation time sane" default the UI now
// uses rather than the (much slower) data-class default.
private const val IMAGE_GEN_STEPS = 6

private val SUPPORTED_SIZES = setOf("512x512")
private const val DEFAULT_SIZE = "512x512"

suspend fun handleImageGenerations(
    call: ApplicationCall,
    modelRegistry: ModelRegistry,
    mutexes: ConcurrentHashMap<String, Mutex>,
    loadModel: suspend (String, String?) -> LoadResult,
) {
    val request = call.receive<ImageGenerationRequest>()

    if (request.prompt.isBlank()) {
        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Missing required 'prompt' field")))
        return
    }

    val n = request.n ?: 1
    if (n != 1) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorEnvelope(ErrorBody(
                message = "Only n=1 is supported on this device -- each image takes minutes to " +
                    "generate, so requests are never looped to satisfy n>1."
            ))
        )
        return
    }

    val size = request.size ?: DEFAULT_SIZE
    if (size !in SUPPORTED_SIZES) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorEnvelope(ErrorBody(message = "Unsupported size '$size'. Supported: ${SUPPORTED_SIZES.joinToString(", ")}"))
        )
        return
    }

    val responseFormat = request.response_format ?: "b64_json"
    when (responseFormat) {
        "url" -> {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(ErrorBody(
                    message = "response_format 'url' is not supported: this local server does not " +
                        "host generated images over HTTP. Use response_format=b64_json (the default)."
                ))
            )
            return
        }
        "b64_json" -> {}
        else -> {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(ErrorBody(message = "Unsupported response_format '$responseFormat'. Supported: b64_json"))
            )
            return
        }
    }

    val (width, height) = size.split("x").let { it[0].toInt() to it[1].toInt() }

    val sdModels = loadedStableDiffusionModels(
        models = modelRegistry.tasks.flatMap { it.models },
        engineOf = modelRegistry::engineOf,
        isLoadedStableDiffusion = { it.instance is StableDiffusion },
    )

    var model = selectStableDiffusionModel(sdModels, request.model)

    if (model == null && request.model != null) {
        val requestedModel = modelRegistry.tasks.flatMap { it.models }.find { it.name == request.model }
        if (requestedModel != null && modelRegistry.engineOf(requestedModel) != ModelEngine.StableDiffusion) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(ErrorBody(message = "Model '${request.model}' is not an image-generation model"))
            )
            return
        }
    }

    // WP: not currently loaded as a StableDiffusion instance -- if a specific model name was
    // requested, try loading it on demand before giving up (matches ChatHandler's pattern).
    if (model == null && request.model != null) {
        when (val result = loadModel(request.model, null)) {
            is LoadResult.Loaded -> {
                val loaded = modelRegistry.tasks
                    .flatMap { it.models }
                    .find { it.name == request.model }
                if (loaded?.instance is StableDiffusion) {
                    model = loaded
                }
            }
            else -> {
                respondLoadError(call, result)
                return
            }
        }
    }

    if (model == null) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorEnvelope(ErrorBody(
                message = "Model '${request.model}' is not loaded for image generation. Available: " +
                    sdModels.joinToString(", ") { it.name }.ifEmpty { "(none loaded)" }
            ))
        )
        return
    }
    val sd = model.instance as? StableDiffusion
    if (sd == null) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            ErrorEnvelope(ErrorBody(
                message = "Model '${request.model}' is not loaded for image generation. Available: " +
                    sdModels.joinToString(", ") { it.name }.ifEmpty { "(none loaded)" }
            ))
        )
        return
    }

    val params = StableDiffusion.GenerationParams(
        prompt = request.prompt,
        width = width,
        height = height,
        steps = IMAGE_GEN_STEPS,
    )

    val guardResult = withBusyGuard(
        mutexes = mutexes,
        key = model.name,
        timeoutMs = IMAGE_GEN_TIMEOUT_MS,
    ) {
        var bitmap: Bitmap? = null
        sd.generateImage(params).collect { progress ->
            if (progress.bitmap != null) bitmap = progress.bitmap
        }
        val finalBitmap = bitmap
        if (finalBitmap == null) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorEnvelope(ErrorBody(
                    message = "Image generation failed (model may have failed to load, or " +
                        "generation returned no result)."
                ))
            )
            return@withBusyGuard
        }
        val stream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        call.respond(
            ImageGenerationResponse(
                created = System.currentTimeMillis() / 1000,
                data = listOf(ImageData(b64_json = b64)),
            )
        )
    }
    when (guardResult) {
        is BusyResult.Busy -> call.respond(
            HttpStatusCode.TooManyRequests,
            ErrorEnvelope(ErrorBody(message = "Model '${model.name}' is busy generating another image"))
        )
        is BusyResult.TimedOut -> call.respond(
            HttpStatusCode.GatewayTimeout,
            ErrorEnvelope(ErrorBody(
                message = "Image generation exceeded the ${IMAGE_GEN_TIMEOUT_MS / 60_000} " +
                    "minute timeout and was cancelled."
            ))
        )
        is BusyResult.Ok -> {}
    }
}

internal fun loadedStableDiffusionModels(
    models: List<Model>,
    engineOf: (Model) -> ModelEngine,
    isLoadedStableDiffusion: (Model) -> Boolean,
): List<Model> =
    models
        .filter { engineOf(it) == ModelEngine.StableDiffusion && isLoadedStableDiffusion(it) }
        .distinctBy { it.name }

internal fun selectStableDiffusionModel(sdModels: List<Model>, requestedName: String?): Model? =
    if (requestedName != null) sdModels.find { it.name == requestedName } else sdModels.firstOrNull()
