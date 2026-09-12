// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * Ported from mobile-server (com.server.edge.gallery) into this project (relay).
 *
 * Changes from the source:
 *  - package rewritten to com.google.ai.edge.gallery
 *  - binds to 127.0.0.1 only (was 0.0.0.0)
 *  - inference now goes through model.runtimeHelper.runInference (relay's per-runtime
 *    dispatch: LiteRT-LM / llama.cpp / AICore) instead of a hardcoded LlmChatModelHelper
 *  - bearer API key auth required on all v1 routes; health is open
 */
package com.google.ai.edge.gallery.relay.server

import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.relay.server.handlers.ON_DEMAND_MODEL_LOAD_TIMEOUT_MS
import com.google.ai.edge.gallery.relay.server.handlers.handleAgentRun
import com.google.ai.edge.gallery.relay.server.handlers.handleAgentTools
import com.google.ai.edge.gallery.relay.server.handlers.handleAnthropicMessages
import com.google.ai.edge.gallery.relay.server.handlers.handleAudioTranscriptions
import com.google.ai.edge.gallery.relay.server.handlers.handleChatCompletion
import com.google.ai.edge.gallery.relay.server.handlers.handleCompletion
import com.google.ai.edge.gallery.relay.server.handlers.handleEmbeddings
import com.google.ai.edge.gallery.relay.server.handlers.handleImageEdits
import com.google.ai.edge.gallery.relay.server.handlers.handleImageGenerations
import com.google.ai.edge.gallery.relay.server.handlers.handleOcr
import com.google.ai.edge.gallery.relay.server.handlers.handleVisionDetect
import com.google.ai.edge.gallery.relay.server.handlers.handleVisionSegment
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// The OpenAI-shaped v1 API surface, installed by OpenAiServer.start() inside its routing {}
// block after plugins and the auth interceptor are in place.
internal fun Route.installOpenAiRoutes(server: OpenAiServer, port: Int) {
    // Bounded timeout for loads triggered implicitly by an unloaded-model request, unlike the
    // explicit load route below which keeps calling loadModel() with its unbounded default.
    val onDemandLoadModel: suspend (String, String?) -> LoadResult = { name, accel ->
        server.loadModel(name, accel, ON_DEMAND_MODEL_LOAD_TIMEOUT_MS)
    }

    get("/health") {
        call.respond(
            mapOf(
                "status" to "ok",
                "bind" to "${server.boundHost}:$port",
                "key" to server.apiKeyFingerprint,
            )
        )
    }

    // Lists every downloaded model, tagged "loaded"/"available" -- a client can discover a model
    // and load it via POST .../load without the app UI ever having opened it.
    get("/v1/models") {
        val models = server.modelRegistry.tasks
            .flatMap { it.models }
            .filter { server.modelRegistry.getModelDownloadStatus(it).status == ModelDownloadStatusType.SUCCEEDED }
            .distinctBy { it.name }
            .map { it.toModelData(server) }

        call.respond(ModelsListResponse(data = models))
    }

    get("/v1/models/{modelId}") {
        val modelId = call.parameters["modelId"]
        val model = server.modelRegistry.tasks
            .flatMap { it.models }
            .find { it.name == modelId && server.modelRegistry.getModelDownloadStatus(it).status == ModelDownloadStatusType.SUCCEEDED }

        if (model == null) {
            call.respond(HttpStatusCode.NotFound, ErrorEnvelope(ErrorBody(message = "Model not found or not downloaded")))
        } else {
            call.respond(model.toModelData(server))
        }
    }

    // Headless model lifecycle -- a client can load/unload a model over the API with
    // nobody touching the app UI. See OpenAiServer.loadModel/unloadModel.
    post("/v1/models/{id}/load") {
        val id = call.parameters["id"]
        if (id.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Missing model id")))
            return@post
        }
        val request = try {
            call.receive<LoadModelRequest>()
        } catch (e: Exception) {
            LoadModelRequest()
        }
        when (val result = server.loadModel(id, request.accelerator)) {
            is LoadResult.Loaded -> call.respond(
                mapOf("id" to result.name, "status" to "loaded", "accelerator" to result.accelerator)
            )
            is LoadResult.NotFound -> call.respond(
                HttpStatusCode.NotFound, ErrorEnvelope(ErrorBody(message = result.message))
            )
            is LoadResult.Busy -> call.respond(
                HttpStatusCode.TooManyRequests, ErrorEnvelope(ErrorBody(message = result.message))
            )
            is LoadResult.Conflict -> call.respond(
                HttpStatusCode.Conflict, ErrorEnvelope(ErrorBody(message = result.message))
            )
            is LoadResult.Error -> call.respond(
                HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = result.message))
            )
            is LoadResult.TimedOut -> call.respond(
                HttpStatusCode.ServiceUnavailable, ErrorEnvelope(ErrorBody(message = result.message))
            )
        }
    }

    post("/v1/models/{id}/unload") {
        val id = call.parameters["id"]
        if (id.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Missing model id")))
            return@post
        }
        server.unloadModel(id)
        call.respond(mapOf("id" to id, "status" to "unloaded"))
    }

    post("/v1/chat/completions") {
        val request = call.receive<ChatCompletionRequest>()
        handleChatCompletion(
            call = call,
            request = request,
            modelRegistry = server.modelRegistry,
            llmSessionManager = server.llmSessionManager,
            modelMutexes = server.modelMutexes,
            parseAccelerator = server::parseAccelerator,
            usesNpuSlot = server::usesNpuSlot,
            ensureAccelerator = { model, accel ->
                when (val result = server.ensureAccelerator(model, accel)) {
                    is AcceleratorResult.Ok -> null
                    is AcceleratorResult.Conflict -> result.heldBy
                }
            },
            loadModel = onDemandLoadModel,
        )
    }

    post("/v1/completions") {
        val request = call.receive<CompletionRequest>()
        handleCompletion(
            call = call,
            request = request,
            modelRegistry = server.modelRegistry,
            modelMutexes = server.modelMutexes,
            parseAccelerator = server::parseAccelerator,
            usesNpuSlot = server::usesNpuSlot,
            ensureAccelerator = { model, accel ->
                when (val result = server.ensureAccelerator(model, accel)) {
                    is AcceleratorResult.Ok -> null
                    is AcceleratorResult.Conflict -> result.heldBy
                }
            },
            loadModel = onDemandLoadModel,
        )
    }

    // EXPERIMENTAL: thin Anthropic-style translator over handleChatCompletion.
    // Non-streaming only -- see handleAnthropicMessages.
    post("/v1/messages") {
        val request = call.receive<AnthropicMessagesRequest>()
        handleAnthropicMessages(
            call = call,
            request = request,
            modelRegistry = server.modelRegistry,
            llmSessionManager = server.llmSessionManager,
            modelMutexes = server.modelMutexes,
            ensureAccelerator = { model, accel ->
                when (val result = server.ensureAccelerator(model, accel)) {
                    is AcceleratorResult.Ok -> null
                    is AcceleratorResult.Conflict -> result.heldBy
                }
            },
            loadModel = onDemandLoadModel,
        )
    }

    post("/v1/audio/transcriptions") {
        handleAudioTranscriptions(call, server.context, server.modelRegistry, server.transcriptionMutexes, onDemandLoadModel)
    }

    post("/v1/images/generations") {
        handleImageGenerations(call, server.modelRegistry, server.imageGenMutexes, onDemandLoadModel)
    }

    // MediaPipe tasks-vision (GPU delegate, CPU fallback). Models are Google MediaPipe downloads,
    // not bundled in the APK -- see vision/ModelCatalog.kt for the 503 "not downloaded" case.
    post("/v1/vision/detect") {
        handleVisionDetect(call, server.context, server.visionDetectMutexes)
    }

    post("/v1/vision/segment") {
        handleVisionSegment(call, server.context, server.visionSegmentMutexes)
    }

    // Bundled ML Kit text recognition, statically linked into the APK -- unlike the two routes
    // above, there is no 503 "not downloaded" case here.
    post("/v1/vision/ocr") {
        handleOcr(call, server.ocrMutexes)
    }

    post("/v1/embeddings") {
        val request = call.receive<EmbeddingsRequest>()
        handleEmbeddings(
            call = call,
            request = request,
            modelRegistry = server.modelRegistry,
            modelMutexes = server.modelMutexes,
            loadModel = onDemandLoadModel,
        )
    }

    // Honest 501: SD image editing is CPU-only/minutes-per-image on this device, so
    // it's not served over HTTP. See handleImageEdits for the message.
    post("/v1/images/edits") {
        handleImageEdits(call)
    }

    // Runs the app's built-in MobileActions tools headlessly (not client-supplied tool calling,
    // which stays a 501 in handleChatCompletion) -- allowlisting/audit logging live in AgentHandler.
    post("/v1/agent/run") {
        val request = call.receive<AgentRunRequest>()
        handleAgentRun(
            call = call,
            request = request,
            context = server.context,
            modelRegistry = server.modelRegistry,
            agentMutexes = server.agentMutexes,
            ensureAccelerator = { model, accel ->
                when (val result = server.ensureAccelerator(model, accel)) {
                    is AcceleratorResult.Ok -> null
                    is AcceleratorResult.Conflict -> result.heldBy
                }
            },
        )
    }

    get("/v1/agent/tools") {
        handleAgentTools(call, server.context)
    }
}
