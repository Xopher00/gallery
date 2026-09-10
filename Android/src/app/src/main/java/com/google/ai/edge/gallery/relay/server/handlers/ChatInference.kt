// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * R4: chat/completion/messages handlers, split out of OpenAiServer.kt (shared accelerator/NPU
 * helpers stay there, threaded in here as params).
 */
package com.google.ai.edge.gallery.relay.server.handlers

import android.graphics.Bitmap
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.relay.server.ChatChoice
import com.google.ai.edge.gallery.relay.server.ChatCompletionResponse
import com.google.ai.edge.gallery.relay.server.ChatMessage
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.LoadResult
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.server.Usage
import com.google.ai.edge.gallery.runtime.TurnTokenUsage
import com.google.ai.edge.gallery.runtime.TurnUsageStore
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

private const val TAG = "AGChatHandler"

// Maps a non-success LoadResult to the same status codes POST /v1/models/{id}/load returns.
// Callers only invoke this for non-Loaded branches; Loaded means "proceed", not "respond".
internal suspend fun respondLoadError(call: ApplicationCall, result: LoadResult) {
    when (result) {
        is LoadResult.NotFound -> call.respond(HttpStatusCode.NotFound, ErrorEnvelope(ErrorBody(message = result.message)))
        is LoadResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, ErrorEnvelope(ErrorBody(message = result.message)))
        is LoadResult.Conflict -> call.respond(HttpStatusCode.InsufficientStorage, ErrorEnvelope(ErrorBody(message = result.message)))
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

internal suspend fun runInferenceBlocking(model: Model, prompt: String, images: List<Bitmap> = emptyList()): ChatCompletionResponse {
    val resultText = collectInferenceText(model, prompt, images)
    val usage = TurnUsageStore.peek(model.name)
    return ChatCompletionResponse(
        id = "chatcmpl-" + UUID.randomUUID().toString(),
        created = System.currentTimeMillis() / 1000,
        model = model.name,
        choices = listOf(
            ChatChoice(
                index = 0,
                message = ChatMessage(role = "assistant", content = resultText),
                finish_reason = "stop"
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
    object Done : StreamEvent()
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
    encodeChunk: (text: String) -> String,
) {
    val events = Channel<StreamEvent>(Channel.UNLIMITED)

    model.runtimeHelper.runInference(
        model = model,
        input = prompt,
        resultListener = { text, done, _ ->
            if (done) {
                events.trySend(StreamEvent.Done)
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
    )

    // A client disconnect throws CancellationException out of events.receive(); finally still
    // closes the channel so no thread is left blocked draining it.
    try {
        for (event in events) {
            when (event) {
                is StreamEvent.Chunk -> {
                    writer.writeStringUtf8("data: ${encodeChunk(event.text)}\n\n")
                    writer.flush()
                }
                is StreamEvent.Done -> {
                    if (encodeUsageChunk != null) {
                        TurnUsageStore.peek(model.name)?.let { usage ->
                            writer.writeStringUtf8("data: ${encodeUsageChunk(usage)}\n\n")
                        }
                    }
                    writer.writeStringUtf8("data: [DONE]\n\n")
                    writer.flush()
                }
                is StreamEvent.Error -> {
                    writer.writeStringUtf8("data: {\"error\": \"${event.message}\"}\n\n")
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
