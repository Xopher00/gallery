// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * R4: chat/completion/messages handlers, split out of OpenAiServer.kt (shared accelerator/NPU
 * helpers stay there, threaded in here as params).
 */
package com.google.ai.edge.gallery.relay.server.handlers

import android.util.Log
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.relay.server.ChatChunkChoice
import com.google.ai.edge.gallery.relay.server.ChatCompletionChunk
import com.google.ai.edge.gallery.relay.server.ChatCompletionRequest
import com.google.ai.edge.gallery.relay.server.ChatDelta
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.LoadResult
import com.google.ai.edge.gallery.relay.server.honestDefaultAcceleratorLabel
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.relay.sessions.openSession
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
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

suspend fun handleChatCompletion(
    call: ApplicationCall,
    request: ChatCompletionRequest,
    modelRegistry: ModelRegistry,
    llmSessionManager: LlmSessionManager,
    modelMutexes: ConcurrentHashMap<String, Mutex>,
    parseAccelerator: (String) -> Accelerator?,
    // Shared predicate, also used by OpenAiServer's NPU-guard; threaded in, not duplicated.
    usesNpuSlot: (Accelerator) -> Boolean,
    // Reuses OpenAiServer's ensureAccelerator/NPU-guard; non-null = model name holding the NPU.
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

    // No request-level timeout by design (matches pre-refactor behavior); stopResponse cancels
    // the engine on client disconnect, fixing the prior orphaned-inference leak.
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
            // NPU backend ignores temperature/top_p/top_k (SamplerConfig is null there); log
            // rather than silently pretend the override took effect.
            Log.w(TAG, "Model '${model.name}' is running on $effectiveAccelLabel; " +
                "temperature/top_p/top_k are ignored by the runtime on this backend.")
        }

        try {
            request.temperature?.let { model.configValues = model.configValues + (ConfigKeys.TEMPERATURE.label to it) }
            request.top_p?.let { model.configValues = model.configValues + (ConfigKeys.TOPP.label to it) }
            request.top_k?.let { model.configValues = model.configValues + (ConfigKeys.TOPK.label to it) }
            request.max_tokens?.let { model.configValues = model.configValues + (ConfigKeys.MAX_TOKENS.label to it) }

            // F3: tool calling isn't supported -- LiteRT-LM's ToolSet only accepts compile-time-
            // declared tools and never surfaces a model's intended call back to the caller.
            if (!request.tools.isNullOrEmpty()) {
                call.respond(
                    HttpStatusCode.NotImplemented,
                    ErrorEnvelope(ErrorBody(
                        message = "Tool calling is not supported: this runtime's tool mechanism " +
                            "(LiteRT-LM ToolSet) only accepts compile-time-declared tools, not " +
                            "arbitrary per-request JSON function schemas, and cannot report a " +
                            "model's intended tool call back to an API caller. Remove 'tools' " +
                            "from the request.",
                        type = "not_implemented_error"
                    ))
                )
                return@withBusyGuard
            }

            val systemMessages = request.messages.filter { it.role == "system" }
            val systemTexts = systemMessages.map { msg ->
                when (val parsed = parseMessageContent(msg.content)) {
                    is ContentParseResult.Ok -> parsed.parsed.text
                    is ContentParseResult.Error -> {
                        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = parsed.message)))
                        return@withBusyGuard
                    }
                }
            }
            val systemInstruction = if (systemTexts.isNotEmpty()) {
                Contents.of(Content.Text(systemTexts.joinToString("\n")))
            } else null

            val conversationMessages = request.messages.filter { it.role != "system" }

            if (conversationMessages.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No user/assistant messages provided"))
                return@withBusyGuard
            }

            // F4: only the LAST message's images go to the runtime; earlier turns' images are
            // dropped during history replay below.
            val lastMessage = conversationMessages.last()
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
                // Role mapping matches DefaultLlmSessionManager.protoToLitertMessage.
                val initialMessages = mutableListOf<Message>()
                for (i in 0 until conversationMessages.size - 1) {
                    val msg = conversationMessages[i]
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

                LlmChatModelHelper.resetConversation(
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
                // openSession seats the conversation itself using the client's id verbatim.
                sessionHistory = openSession(
                    sessionManager = llmSessionManager,
                    sessionId = request.session_id,
                    taskId = taskIdFor(modelRegistry, model),
                    model = model,
                    supportImage = model.llmSupportImage,
                    supportAudio = false,
                    defaultSystemPrompt = systemTexts.firstOrNull(),
                ).messages
            }

            val prompt = lastParsed.text

            suspend fun persistTurn(assistantText: String) {
                val sid = effectiveSessionId ?: return
                llmSessionManager.saveSessionHistory(
                    sessionId = sid,
                    messages = sessionHistory +
                        textTurnProto(ChatSideProto.CHAT_SIDE_USER, prompt) +
                        textTurnProto(ChatSideProto.CHAT_SIDE_MODEL, assistantText),
                    originalModel = model.name,
                    taskId = taskIdFor(modelRegistry, model),
                )
            }

            if (request.stream) {
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    val id = "chatcmpl-" + UUID.randomUUID().toString()
                    val created = System.currentTimeMillis() / 1000
                    val assistantText = StringBuilder()
                    collectInferenceStream(this, model, prompt, lastParsed.images) { text ->
                        assistantText.append(text)
                        Json.encodeToString(
                            ChatCompletionChunk(
                                id = id,
                                created = created,
                                model = model.name,
                                choices = listOf(
                                    ChatChunkChoice(
                                        index = 0,
                                        delta = ChatDelta(content = text)
                                    )
                                )
                            )
                        )
                    }
                    persistTurn(assistantText.toString())
                }
            } else {
                val response = runInferenceBlocking(model, prompt, lastParsed.images)
                persistTurn(response.choices.first().message.content)
                call.respond(
                    if (effectiveSessionId != null) response.copy(session_id = effectiveSessionId) else response
                )
            }
        } finally {
            model.configValues = originalConfigValues
        }
    }
    when (guardResult) {
        is BusyResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Model is busy"))
        is BusyResult.TimedOut -> {} // NO_BUSY_GUARD_TIMEOUT_MS is not expected to elapse
        is BusyResult.Ok -> {}
    }
}
