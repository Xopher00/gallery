// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * R4: chat/completion/messages handlers, split out of OpenAiServer.kt (shared accelerator/NPU
 * helpers stay there, threaded in here as params).
 */
package com.google.ai.edge.gallery.relay.server.handlers

import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.server.CompletionChoice
import com.google.ai.edge.gallery.relay.server.CompletionChunk
import com.google.ai.edge.gallery.relay.server.CompletionChunkChoice
import com.google.ai.edge.gallery.relay.server.CompletionRequest
import com.google.ai.edge.gallery.relay.server.CompletionResponse
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.LoadResult
import com.google.ai.edge.gallery.relay.server.honestDefaultAcceleratorLabel
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.runtime.runtimeHelper
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "AGChatHandler"

suspend fun handleCompletion(
    call: ApplicationCall,
    request: CompletionRequest,
    modelRegistry: ModelRegistry,
    modelMutexes: ConcurrentHashMap<String, Mutex>,
    parseAccelerator: (String) -> Accelerator?,
    usesNpuSlot: (Accelerator) -> Boolean,
    ensureAccelerator: suspend (Model, Accelerator?) -> String?,
    loadModel: suspend (String, String?) -> LoadResult,
) {
    val model = modelRegistry.tasks
        .flatMap { it.models }
        .find { it.name == request.model }

    if (model == null) {
        call.respond(HttpStatusCode.NotFound, ErrorEnvelope(ErrorBody(message = "Unknown model '${request.model}'")))
        return
    }

    if (model.instance == null) {
        val result = loadModel(request.model, request.accelerator)
        if (result !is LoadResult.Loaded) {
            respondLoadError(call, result)
            return
        }
    }

    val requestedAccelerator = request.accelerator?.let { raw ->
        parseAccelerator(raw) ?: run {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(ErrorBody(
                    message = "Invalid accelerator '$raw'. Valid values: " +
                        Accelerator.values().joinToString(", ") { it.label.lowercase() }
                ))
            )
            return
        }
    }

    // No request-level timeout by design; stopResponse cancels the engine on client disconnect.
    val guardResult = withBusyGuard(
        mutexes = modelMutexes,
        key = model.name,
        timeoutMs = NO_BUSY_GUARD_TIMEOUT_MS,
        onCancel = { model.runtimeHelper.stopResponse(model) },
    ) {
        // Snapshot before ensureAccelerator (which may reinitialize and mutate configValues) so
        // finally restores the prior PREFERENCE only -- it does not undo an actual reinitialize.
        val originalConfigValues = model.configValues
        val heldBy = ensureAccelerator(model, requestedAccelerator)
        if (heldBy != null) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorEnvelope(ErrorBody(
                    message = "NPU is currently held by model '$heldBy'; only one " +
                        "model may use the NPU at a time on this device."
                ))
            )
            return@withBusyGuard
        }

        val effectiveAccelLabel = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = honestDefaultAcceleratorLabel(model))
        val effectiveAccel = parseAccelerator(effectiveAccelLabel)
        if (effectiveAccel != null && usesNpuSlot(effectiveAccel) &&
            (request.temperature != null || request.top_p != null || request.top_k != null)
        ) {
            Log.w(TAG, "Model '${model.name}' is running on $effectiveAccelLabel; " +
                "temperature/top_p/top_k are ignored by the runtime on this backend.")
        }

        if (request.max_tokens != null && request.max_tokens < 1) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(ErrorBody(message = "max_tokens must be at least 1"))
            )
            return@withBusyGuard
        }
        withSamplerOverrides(model, originalConfigValues, request.temperature, request.top_p, request.top_k) {
            if (request.stream) {
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    val id = "cmpl-" + UUID.randomUUID().toString()
                    val created = System.currentTimeMillis() / 1000
                    collectInferenceStream(this, model, request.prompt, maxOutputTokens = request.max_tokens) { text ->
                        Json.encodeToString(
                            CompletionChunk(
                                id = id,
                                created = created,
                                model = model.name,
                                choices = listOf(
                                    CompletionChunkChoice(
                                        index = 0,
                                        text = text
                                    )
                                )
                            )
                        )
                    }
                }
            } else {
                var truncated = false
                val responseText = collectInferenceText(
                    model, request.prompt,
                    maxOutputTokens = request.max_tokens,
                    onTruncated = { truncated = true },
                )
                call.respond(CompletionResponse(
                    id = "cmpl-" + UUID.randomUUID().toString(),
                    created = System.currentTimeMillis() / 1000,
                    model = model.name,
                    choices = listOf(
                        CompletionChoice(
                            index = 0,
                            text = responseText,
                            finish_reason = if (truncated) "length" else "stop"
                        )
                    )
                ))
            }
        }
    }
    when (guardResult) {
        is BusyResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Model is busy"))
        is BusyResult.TimedOut -> {} // NO_BUSY_GUARD_TIMEOUT_MS is not expected to elapse
        is BusyResult.Ok -> {}
    }
}
