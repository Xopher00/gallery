// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * R4: chat/completion/messages handlers, split out of OpenAiServer.kt (shared accelerator/NPU
 * helpers stay there, threaded in here as params).
 */
package com.google.ai.edge.gallery.relay.server.handlers

import android.util.Log
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.relay.server.AnthropicContentBlock
import com.google.ai.edge.gallery.relay.server.AnthropicMessagesRequest
import com.google.ai.edge.gallery.relay.server.AnthropicMessagesResponse
import com.google.ai.edge.gallery.relay.server.AnthropicUsage
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.LoadResult
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.runtime.TurnUsageStore
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.relay.sessions.openSession
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

private const val TAG = "AGChatHandler"

// F3, EXPERIMENTAL: thin Anthropic-shape /v1/messages translator over the same handler
// plumbing as handleChatCompletion. Streaming (SSE) is not implemented -- stream:true is a 501.
suspend fun handleAnthropicMessages(
    call: ApplicationCall,
    request: AnthropicMessagesRequest,
    modelRegistry: ModelRegistry,
    llmSessionManager: LlmSessionManager,
    modelMutexes: ConcurrentHashMap<String, Mutex>,
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
        // No per-request accelerator field on this request shape -- pass null, same as elsewhere.
        val result = loadModel(request.model, null)
        if (result !is LoadResult.Loaded) {
            respondLoadError(call, result)
            return
        }
    }

    if (!request.tools.isNullOrEmpty()) {
        call.respond(
            HttpStatusCode.NotImplemented,
            ErrorEnvelope(ErrorBody(
                message = "Tool calling is not supported on /v1/messages either -- same runtime " +
                    "limitation as /v1/chat/completions (see its 501 for 'tools'). Remove 'tools' " +
                    "from the request.",
                type = "not_implemented_error"
            ))
        )
        return
    }

    if (request.stream) {
        call.respond(
            HttpStatusCode.NotImplemented,
            ErrorEnvelope(ErrorBody(
                message = "/v1/messages streaming (SSE with Anthropic event names) is not " +
                    "implemented; set stream:false and use the non-streaming response.",
                type = "not_implemented_error"
            ))
        )
        return
    }

    if (request.messages.isEmpty()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No messages provided"))
        return
    }

    val guardResult = withBusyGuard(
        mutexes = modelMutexes,
        key = model.name,
        timeoutMs = NO_BUSY_GUARD_TIMEOUT_MS,
        onCancel = { model.runtimeHelper.stopResponse(model) },
    ) {
        val heldBy = ensureAccelerator(model, null)
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

        val originalConfigValues = model.configValues
        val replyCap = request.max_tokens
        withSamplerOverrides(model, originalConfigValues, request.temperature, request.top_p, request.top_k) {
            // Anthropic's `system` is top-level, not a message role.
            val systemInstruction = request.system?.let { Contents.of(Content.Text(it)) }

            val lastMessage = request.messages.last()
            if (lastMessage.role != "user") {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Last message must be from user"))
                return@withBusyGuard
            }

            val lastParsed = when (val parsed = parseMessageContent(lastMessage.content)) {
                is ContentParseResult.Ok -> parsed.parsed
                is ContentParseResult.Error -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = parsed.message)))
                    return@withBusyGuard
                }
            }
            if (lastParsed.images.isNotEmpty() && !model.llmSupportImage) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(ErrorBody(
                        message = "Model '${model.name}' does not accept image input " +
                            "(llmSupportImage is false); remove the image_url content part(s) " +
                            "or use a vision-capable model."
                    ))
                )
                return@withBusyGuard
            }

            // session_id present -> saved chat is the context, only the last message is new.
            var effectiveSessionId: String? = null
            var sessionHistory: List<ChatMessageProto> = emptyList()
            if (request.session_id == null) {
                val initialMessages = mutableListOf<Message>()
                for (i in 0 until request.messages.size - 1) {
                    val msg = request.messages[i]
                    val parsedHistory = when (val parsed = parseMessageContent(msg.content)) {
                        is ContentParseResult.Ok -> parsed.parsed
                        is ContentParseResult.Error -> {
                            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = parsed.message)))
                            return@withBusyGuard
                        }
                    }
                    when (msg.role) {
                        "user" -> initialMessages.add(Message.user(parsedHistory.text))
                        "assistant" -> initialMessages.add(Message.model(parsedHistory.text))
                        else -> Log.w(TAG, "Unknown role '${msg.role}' in message history, skipping")
                    }
                }

                model.runtimeHelper.resetConversation(
                    model = model,
                    supportImage = model.llmSupportImage,
                    supportAudio = false,
                    systemInstruction = systemInstruction,
                    tools = emptyList(),
                    enableConversationConstrainedDecoding = false,
                    initialMessages = initialMessages
                )
            } else {
                effectiveSessionId = request.session_id
                sessionHistory = openSession(
                    sessionManager = llmSessionManager,
                    sessionId = request.session_id,
                    taskId = taskIdFor(modelRegistry, model),
                    model = model,
                    supportImage = model.llmSupportImage,
                    supportAudio = false,
                    defaultSystemPrompt = request.system,
                ).messages
            }

            var truncated = false
            val resultText =
                collectInferenceText(
                    model, lastParsed.text, lastParsed.images,
                    maxOutputTokens = replyCap,
                    onTruncated = { truncated = true },
                )

            if (effectiveSessionId != null) {
                llmSessionManager.saveSessionHistory(
                    sessionId = effectiveSessionId,
                    messages = sessionHistory +
                        textTurnProto(ChatSideProto.CHAT_SIDE_USER, lastParsed.text) +
                        textTurnProto(ChatSideProto.CHAT_SIDE_MODEL, resultText),
                    originalModel = model.name,
                    taskId = taskIdFor(modelRegistry, model),
                )
            }

            call.respond(
                AnthropicMessagesResponse(
                    id = "msg_" + UUID.randomUUID().toString(),
                    model = model.name,
                    content = listOf(AnthropicContentBlock(text = resultText)),
                    stop_reason = if (truncated) "max_tokens" else "end_turn",
                    // 0/0 when the engine recorded nothing for this turn: unknown, not faked.
                    usage =
                        TurnUsageStore.peek(model.name).let { usage ->
                            AnthropicUsage(
                                input_tokens = usage?.prompt?.tokens ?: 0,
                                output_tokens = usage?.completion?.tokens ?: 0,
                            )
                        },
                    session_id = effectiveSessionId,
                )
            )
        }
    }
    when (guardResult) {
        is BusyResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Model is busy"))
        is BusyResult.TimedOut -> {} // NO_BUSY_GUARD_TIMEOUT_MS is not expected to elapse
        is BusyResult.Ok -> {}
    }
}
