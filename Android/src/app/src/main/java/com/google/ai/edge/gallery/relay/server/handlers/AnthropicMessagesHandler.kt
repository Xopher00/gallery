// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * R4: chat/completion/messages handlers, split out of OpenAiServer.kt (shared accelerator/NPU
 * helpers stay there, threaded in here as params).
 */
package com.google.ai.edge.gallery.relay.server.handlers

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.relay.server.AnthropicMessagesRequest
import com.google.ai.edge.gallery.relay.server.AnthropicMessagesResponse
import com.google.ai.edge.gallery.relay.server.AnthropicUsage
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.LoadResult
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.runtime.ThermalGovernor
import com.google.ai.edge.gallery.relay.runtime.TurnUsageStore
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.relay.sessions.openSession
import com.google.ai.edge.gallery.relay.vision.VisionToolListing
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex

private const val TAG = "AGChatHandler"

// F3, EXPERIMENTAL: thin Anthropic-shape /v1/messages translator over the same handler
// plumbing as handleChatCompletion. Streaming (SSE) is not implemented -- stream:true is a 501.
suspend fun handleAnthropicMessages(
    call: ApplicationCall,
    request: AnthropicMessagesRequest,
    context: Context,
    modelRegistry: ModelRegistry,
    llmSessionManager: LlmSessionManager,
    modelMutexes: ConcurrentHashMap<String, Mutex>,
    ensureAccelerator: suspend (Model, Accelerator?) -> String?,
    loadModel: suspend (String, String?, Int?) -> LoadResult,
) {
    val model = modelRegistry.getModelByName(request.model)

    if (model == null) {
        VisionToolListing.findById(context, request.model)?.let {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = VisionToolListing.misdirectedTextRequestMessage(it))))
            return
        }
        call.respond(HttpStatusCode.NotFound, ErrorEnvelope(ErrorBody(message = "Unknown model '${request.model}'")))
        return
    }

    when (val decision = ThermalGovernor.gateDecision(context)) {
        is ThermalGovernor.GateDecision.Shed -> {
            call.response.header(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorEnvelope(ErrorBody(message = "Device is too hot; retry later")))
            return
        }
        ThermalGovernor.GateDecision.Delay -> delay(ThermalGovernor.MODERATE_START_DELAY_MS)
        ThermalGovernor.GateDecision.Proceed -> {}
    }

    gpuLayersRangeError(request.gpu_layers)?.let {
        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = it)))
        return
    }

    if (model.instance == null || needsGpuLayersReload(model, request.gpu_layers)) {
        // No per-request accelerator field on this request shape -- pass null, same as elsewhere.
        val result = loadModel(request.model, null, request.gpu_layers)
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

    if (request.stream == true) {
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
        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "No messages provided")))
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
        replyCapError(replyCap)?.let {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = it)))
            return@withBusyGuard
        }
        samplerRangeError(request.temperature, request.top_p, request.top_k)?.let {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = it)))
            return@withBusyGuard
        }
        withSamplerOverrides(model, originalConfigValues, request.temperature, request.top_p, request.top_k) {
            // Anthropic's `system` is top-level, not a message role.
            val systemText = anthropicSystemText(request.system)
            val systemInstruction = systemText?.let { Contents.of(Content.Text(it)) }

            val lastMessage = request.messages.last()
            if (lastMessage.role != "user") {
                call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Last message must be from user")))
                return@withBusyGuard
            }

            val lastParsed = when (val parsed = parseAnthropicMessage(lastMessage.role, lastMessage.content)) {
                is AnthropicParseResult.Ok -> parsed
                is AnthropicParseResult.Error -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = parsed.message)))
                    return@withBusyGuard
                }
            }
            // No audio block type in Anthropic's content schema; reuse MultimodalContent's decoder for images.
            val decodedImages = when (val decoded = parseMessageContent(lastParsed.openAiContent)) {
                is ContentParseResult.Ok -> decoded.parsed.images
                is ContentParseResult.Error -> {
                    call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = decoded.message)))
                    return@withBusyGuard
                }
            }
            if (decodedImages.isNotEmpty() && !model.supportImage) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorEnvelope(ErrorBody(
                        message = "Model '${model.name}' does not accept image input " +
                            "(supportImage is false); remove the image_url content part(s) " +
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
                    val parsedHistory = when (val parsed = parseAnthropicMessage(msg.role, msg.content)) {
                        is AnthropicParseResult.Ok -> parsed
                        is AnthropicParseResult.Error -> {
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
                    supportImage = model.supportImage,
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
                    supportImage = model.supportImage,
                    supportAudio = false,
                    defaultSystemPrompt = anthropicSystemText(request.system),
                ).messages
            }

            var truncated = false
            val startMs = System.currentTimeMillis()
            val stopWatcher = ThermalStopWatcher(model)
            val resultText =
                collectInferenceText(
                    model, lastParsed.text, decodedImages,
                    maxOutputTokens = replyCap,
                    onTruncated = { truncated = true },
                    onPartial = stopWatcher::onPartial,
                )
            if (stopWatcher.triggered) truncated = true
            feedDecodeRate(model, System.currentTimeMillis() - startMs)

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
                    content = anthropicContentBlocks(resultText, emptyList(), emptyList()),
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
        is BusyResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, ErrorEnvelope(ErrorBody(message = "Model is busy")))
        is BusyResult.TimedOut -> {} // NO_BUSY_GUARD_TIMEOUT_MS is not expected to elapse
        is BusyResult.Ok -> {}
    }
}
