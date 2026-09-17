// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * R4: chat/completion/messages handlers, split out of OpenAiServer.kt (shared accelerator/NPU
 * helpers stay there, threaded in here as params).
 */
package com.google.ai.edge.gallery.relay.server.handlers

import android.graphics.Bitmap
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.relay.server.ChatChoice
import com.google.ai.edge.gallery.relay.server.ChatCompletionResponse
import com.google.ai.edge.gallery.relay.server.ChatMessage
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.LoadResult
import com.google.ai.edge.gallery.relay.server.ResponseFormat
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.runtime.ModelEngine
import com.google.ai.edge.gallery.relay.runtime.isContextOverflow
import com.google.ai.edge.gallery.relay.server.Usage
import com.google.ai.edge.gallery.relay.runtime.ThermalGovernor
import com.google.ai.edge.gallery.relay.runtime.TurnTokenUsage
import com.google.ai.edge.gallery.relay.runtime.TurnUsageStore
import com.google.ai.edge.gallery.relay.runtime.llamacpp.LlamaCppEngine
import com.google.ai.edge.gallery.relay.runtime.llamacpp.LlamaCppModelHelper
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.runtimeHelper
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "AGChatHandler"

// An out-of-range value here closes the runtime's conversation permanently, not just this request.
internal fun samplerRangeError(temperature: Float?, topP: Float?, topK: Int?): String? {
    if (topK != null && topK < 1) return "top_k must be at least 1"
    if (topP != null && (topP < 0 || topP > 1)) return "top_p must be between 0 and 1"
    if (temperature != null && temperature < 0) return "temperature must be at least 0"
    return null
}

internal fun replyCapError(cap: Int?): String? = if (cap != null && cap < 1) "max_tokens must be at least 1" else null

internal fun gpuLayersRangeError(gpuLayers: Int?): String? =
    if (gpuLayers != null && gpuLayers !in 0..999) "gpu_layers must be between 0 and 999" else null

internal fun structuredOutputStreamConflictError(stream: Boolean, responseFormat: ResponseFormat?): String? =
    if (stream && responseFormat != null) {
        "stream and response_format cannot be combined: a streamed reply is already sent to the " +
            "client before it could be validated against the schema."
    } else null

internal fun structuredOutputEngineError(engine: ModelEngine, responseFormat: ResponseFormat?): String? =
    if (responseFormat != null && (engine == ModelEngine.AiCore || engine == ModelEngine.LlamaCpp)) {
        "cannot honor response_format: its engine (${engine.wireName}) does not support " +
            "constrained decoding yet."
    } else null

// null counts as 0 (CPU); a live llama.cpp engine loaded on a different value must be reloaded.
internal fun needsGpuLayersReload(model: Model, requestedGpuLayers: Int?): Boolean =
    model.instance is LlamaCppEngine &&
        (LlamaCppModelHelper.gpuLayersFor(model.name) ?: 0) != (requestedGpuLayers ?: 0)

// inline (not suspend lambda) -- handler bodies contain non-local returns like
// `return@withBusyGuard`, which only thread through an inlined block.
internal inline fun <T> withSamplerOverrides(
    model: Model,
    original: Map<String, Any>,
    temperature: Float?,
    topP: Float?,
    topK: Int?,
    block: () -> T,
): T {
    temperature?.let { model.configValues = model.configValues + (ConfigKeys.TEMPERATURE.label to it) }
    topP?.let { model.configValues = model.configValues + (ConfigKeys.TOPP.label to it) }
    topK?.let { model.configValues = model.configValues + (ConfigKeys.TOPK.label to it) }
    return try { block() } finally { model.configValues = original }
}

// Maps a non-success LoadResult to the same status codes POST /v1/models/{id}/load returns.
// Callers only invoke this for non-Loaded branches; Loaded means "proceed", not "respond".
internal suspend fun respondLoadError(call: ApplicationCall, result: LoadResult) {
    when (result) {
        is LoadResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorEnvelope(ErrorBody(message = result.message)))
        is LoadResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, ErrorEnvelope(ErrorBody(message = result.message)))
        is LoadResult.Conflict -> call.respond(HttpStatusCode.Conflict, ErrorEnvelope(ErrorBody(message = result.message)))
        is LoadResult.Error -> call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = result.message)))
        is LoadResult.TimedOut -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorEnvelope(ErrorBody(message = result.message)))
        is LoadResult.Loaded -> {} // caller shouldn't reach here for the success case
    }
}

// Text-only proto builder for session persistence; this server only ever persists TEXT turns.
internal fun textTurnProto(side: ChatSideProto, content: String): ChatMessageProto =
    ChatMessageProto.newBuilder()
        .setMessageType("TEXT")
        .setContent(content)
        .setSide(side)
        .build()

// Falls back to the generic chat task id for an imported model attached to no curated task.
internal fun taskIdFor(modelRegistry: ModelRegistry, model: Model): String =
    modelRegistry.tasks.find { t -> t.models.any { it.name == model.name } }?.id ?: BuiltInTaskId.LLM_CHAT

// The engines record token counts; this only reshapes them into the wire DTO.
internal fun TurnTokenUsage.toUsage(): Usage =
    Usage(
        prompt_tokens = prompt.tokens,
        completion_tokens = completion.tokens,
        total_tokens = total,
    )

internal fun TurnTokenUsage.exactnessLabel(): String = if (isFullyExact) "exact" else "estimated"

// Shared by non-streaming call sites outside collectInferenceStream's own Done handling.
internal fun feedDecodeRate(model: Model, elapsedMs: Long) {
    val tokens = TurnUsageStore.peek(model.name)?.completion?.tokens ?: return
    if (tokens <= 0 || elapsedMs <= 0) return
    ThermalGovernor.recordDecodeRate(tokens * 1000.0 / elapsedMs)
}

// Once-per-turn stop, called from onPartial or a stream's own chunk loop. [triggered] then folds
// into the caller's truncated/finish_reason so a thermal stop reports "length" like a token cap.
internal class ThermalStopWatcher(private val model: Model) {
    var triggered: Boolean = false
        private set

    fun onPartial(@Suppress("UNUSED_PARAMETER") text: String) {
        if (!triggered && ThermalGovernor.shouldStopNow()) {
            triggered = true
            model.runtimeHelper.stopResponse(model)
        }
    }
}

internal suspend fun runInferenceBlocking(model: Model, prompt: String, images: List<Bitmap> = emptyList(), maxOutputTokens: Int? = null): ChatCompletionResponse {
    var truncated = false
    val startMs = System.currentTimeMillis()
    val stopWatcher = ThermalStopWatcher(model)
    val resultText = collectInferenceText(
        model, prompt, images,
        maxOutputTokens = maxOutputTokens,
        onTruncated = { truncated = true },
        onPartial = stopWatcher::onPartial,
    )
    if (stopWatcher.triggered) truncated = true
    feedDecodeRate(model, System.currentTimeMillis() - startMs)
    return buildChatCompletionResponse(model, resultText, truncated)
}

internal fun buildChatCompletionResponse(model: Model, text: String, truncated: Boolean): ChatCompletionResponse {
    val usage = TurnUsageStore.peek(model.name)
    return ChatCompletionResponse(
        id = "chatcmpl-" + UUID.randomUUID().toString(),
        created = System.currentTimeMillis() / 1000,
        model = model.name,
        choices = listOf(
            ChatChoice(
                index = 0,
                message = ChatMessage(role = "assistant", content = text),
                finish_reason = if (truncated) "length" else "stop"
            )
        ),
        usage = usage?.toUsage(),
        x_box_usage_exactness = usage?.exactnessLabel(),
    )
}

// Events the resultListener/onError callbacks (on the runtime's own callback thread) hand off
// to the drain loop; trySend on an UNLIMITED channel never blocks that thread on client I/O.
private sealed class StreamEvent {
    data class Chunk(val text: String) : StreamEvent()
    data class Done(val truncated: Boolean) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

// Shared streaming core for handleChatCompletion/handleCompletion; encodeChunk builds the
// caller-specific SSE payload from each piece of text.
internal suspend fun collectInferenceStream(
    writer: ByteWriteChannel,
    model: Model,
    prompt: String,
    images: List<Bitmap> = emptyList(),
    // Endpoints whose chunk shape carries no usage field simply leave this null.
    encodeUsageChunk: ((usage: TurnTokenUsage) -> String)? = null,
    // Sent regardless of encodeUsageChunk -- finish_reason must not depend on include_usage.
    // Param: true iff generation was cut off by maxOutputTokens rather than stopping on its own.
    encodeFinishChunk: ((truncated: Boolean) -> String)? = null,
    maxOutputTokens: Int? = null,
    helper: LlmModelHelper = model.runtimeHelper,
    encodeChunk: (text: String) -> String,
) {
    val events = Channel<StreamEvent>(Channel.UNLIMITED)
    val startMs = System.currentTimeMillis()
    val stopWatcher = ThermalStopWatcher(model)

    helper.runInference(
        model = model,
        input = prompt,
        resultListener = { text, done, _ ->
            if (done) {
                // The engine's own count, not a callback tally: llama.cpp suppresses empty deltas.
                val emitted = TurnUsageStore.peek(model.name)?.completion?.tokens ?: 0
                events.trySend(StreamEvent.Done(truncated = maxOutputTokens != null && emitted >= maxOutputTokens))
                events.close()
            } else {
                events.trySend(StreamEvent.Chunk(text))
            }
        },
        cleanUpListener = {},
        onError = {
            events.trySend(StreamEvent.Error(it))
            events.close()
        },
        images = images,
        audioClips = emptyList(),
        coroutineScope = CoroutineScope(Dispatchers.Default),
        extraContext = null,
        maxOutputTokens = maxOutputTokens,
    )

    // A client disconnect throws CancellationException out of events.receive(); finally still
    // closes the channel so no thread is left blocked draining it.
    try {
        for (event in events) {
            when (event) {
                is StreamEvent.Chunk -> {
                    stopWatcher.onPartial(event.text)
                    writer.writeStringUtf8("data: ${encodeChunk(event.text)}\n\n")
                    writer.flush()
                }
                is StreamEvent.Done -> {
                    feedDecodeRate(model, System.currentTimeMillis() - startMs)
                    val usage = TurnUsageStore.peek(model.name)
                    val truncated = event.truncated || stopWatcher.triggered
                    if (encodeFinishChunk != null) {
                        writer.writeStringUtf8("data: ${encodeFinishChunk(truncated)}\n\n")
                    }
                    if (encodeUsageChunk != null && usage != null) {
                        writer.writeStringUtf8("data: ${encodeUsageChunk(usage)}\n\n")
                    }
                    writer.writeStringUtf8("data: [DONE]\n\n")
                    writer.flush()
                }
                is StreamEvent.Error -> {
                    val payload = Json.encodeToString(
                        ErrorEnvelope(
                            ErrorBody(
                                message = event.message,
                                code = if (isContextOverflow(event.message)) "context_length_exceeded" else null,
                            )
                        )
                    )
                    writer.writeStringUtf8("data: $payload\n\n")
                    writer.writeStringUtf8("data: [DONE]\n\n")
                    writer.flush()
                    throw Exception(event.message)
                }
            }
        }
    } finally {
        events.close()
    }
}
