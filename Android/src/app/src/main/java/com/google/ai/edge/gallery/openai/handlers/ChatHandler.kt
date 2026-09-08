/*
 * R4: chat/completion/messages route handlers, split out of OpenAiServer.kt at the same seam
 * every other route already crossed (see ImageGenerationHandler.kt, AgentHandler.kt, etc.).
 * Pure move -- no behaviour change. See OpenAiServer.kt for the routing table, server
 * construction, bearer-auth, /health, /v1/models, and the shared accelerator/NPU-guard helpers
 * (parseAccelerator, ensureAccelerator, reinitializeModel) that stay there because non-chat
 * routes (e.g. /v1/agent/run) also depend on them -- they are threaded into this file as
 * function parameters (`parseAccelerator`, `ensureAccelerator`) exactly like AgentHandler.kt
 * already does for `ensureAccelerator`.
 */
package com.google.ai.edge.gallery.openai.handlers

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.openai.AnthropicContentBlock
import com.google.ai.edge.gallery.openai.AnthropicMessagesRequest
import com.google.ai.edge.gallery.openai.AnthropicMessagesResponse
import com.google.ai.edge.gallery.openai.AnthropicUsage
import com.google.ai.edge.gallery.openai.ChatChoice
import com.google.ai.edge.gallery.openai.ChatChunkChoice
import com.google.ai.edge.gallery.openai.ChatCompletionChunk
import com.google.ai.edge.gallery.openai.ChatCompletionRequest
import com.google.ai.edge.gallery.openai.ChatCompletionResponse
import com.google.ai.edge.gallery.openai.ChatDelta
import com.google.ai.edge.gallery.openai.ChatMessage
import com.google.ai.edge.gallery.openai.CompletionChoice
import com.google.ai.edge.gallery.openai.CompletionChunk
import com.google.ai.edge.gallery.openai.CompletionChunkChoice
import com.google.ai.edge.gallery.openai.CompletionRequest
import com.google.ai.edge.gallery.openai.CompletionResponse
import com.google.ai.edge.gallery.openai.ErrorBody
import com.google.ai.edge.gallery.openai.ErrorEnvelope
import com.google.ai.edge.gallery.openai.LoadResult
import com.google.ai.edge.gallery.openai.honestDefaultAcceleratorLabel
import com.google.ai.edge.gallery.relay.modelmanager.ModelRegistry
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.sessions.openSession
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
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "AGChatHandler"

// WP: shared by handleChatCompletion/handleCompletion/handleAnthropicMessages below AND
// (internal, so visible module-wide) ImageGenerationHandler/AudioTranscriptionHandler -- maps
// every non-success OpenAiServer.LoadResult from an on-demand loadModel() call to the same
// status codes POST /v1/models/{id}/load itself returns (see OpenAiServer's route). Callers
// only invoke this for the non-Loaded branches; LoadResult.Loaded means "proceed", not "respond".
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

// Box: text-only proto builder for session persistence. Mirrors ChatViewModel.saveSession's
// ChatMessageProto.Builder field names/enum values (ui/common/chat/ChatViewModel.kt:436) --
// this server never persists THINKING/INFO/WARNING message types, only TEXT turns.
private fun textTurnProto(side: ChatSideProto, content: String): ChatMessageProto =
    ChatMessageProto.newBuilder()
        .setMessageType("TEXT")
        .setContent(content)
        .setSide(side)
        .build()

// Box: the task this model is curated under, if any -- same lookup OpenAiServer.unloadModel
// uses (modelRegistry.tasks.find { t -> t.models.any { it.name == name } }). Falls back to the
// generic chat task id for an imported model attached to no curated task.
private fun taskIdFor(modelRegistry: ModelRegistry, model: Model): String =
    modelRegistry.tasks.find { t -> t.models.any { it.name == model.name } }?.id ?: BuiltInTaskId.LLM_CHAT

suspend fun handleChatCompletion(
    call: ApplicationCall,
    request: ChatCompletionRequest,
    modelRegistry: ModelRegistry,
    llmSessionManager: LlmSessionManager,
    modelMutexes: ConcurrentHashMap<String, Mutex>,
    parseAccelerator: (String) -> Accelerator?,
    // Shared one-line predicate (also used by OpenAiServer's ensureAccelerator/NPU-guard) --
    // threaded in rather than duplicated, same pattern as parseAccelerator/ensureAccelerator.
    usesNpuSlot: (Accelerator) -> Boolean,
    // Reuses OpenAiServer's private ensureAccelerator/NPU-guard logic (bound by the caller) --
    // null means "ok to proceed", a non-null String is the model name currently holding the NPU.
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

    // This route never had a request-level timeout before this refactor (it ran until the
    // model finished or the client disconnected), so NO_BUSY_GUARD_TIMEOUT_MS preserves that
    // rather than introducing a new 504 this route never returned. model.runtimeHelper.
    // stopResponse(model) IS a real cancel (LlmModelHelper.stopResponse -> instance.
    // conversation.cancelProcess()) -- it now fires whenever this doesn't finish normally
    // (i.e. the client disconnects mid-generation), fixing the orphaned-inference defect for
    // this endpoint.
    val guardResult = withBusyGuard(
        mutexes = modelMutexes,
        key = model.name,
        timeoutMs = NO_BUSY_GUARD_TIMEOUT_MS,
        onCancel = { model.runtimeHelper.stopResponse(model) },
    ) {
        // Snapshot BEFORE ensureAccelerator below, not after: ensureAccelerator can call
        // reinitializeModel, which mutates model.configValues[ACCELERATOR] to whatever
        // `requestedAccelerator` this request carried (see OpenAiServer.reinitializeModel).
        // Capturing the snapshot first means it holds the accelerator this model was actually
        // configured to before this request, so the finally below restores that -- a per-request
        // accelerator override is applied for this request only and does not durably change the
        // model's recorded PREFERENCE. (POST /v1/models/{id}/load is the one path that still
        // changes it durably; it does not go through this function.) This is also still where the
        // request's ephemeral sampling overrides (temperature/top_p/top_k/max_tokens) get
        // snapshotted, same as before -- fixes the pre-existing data race where concurrent
        // requests / the app UI reading this model could see another request's values.
        //
        // What this restore does NOT do: undo the actual reinitialize. If ensureAccelerator did
        // reinitialize the engine onto a different accelerator for this request, that engine
        // instance keeps running on it after the restore -- only the recorded config label (the
        // PREFERENCE) goes back to the previous value. Reinitializing back here would double the
        // cost of every accelerator-overriding request, so this deliberately does not do that.
        // This is why /v1/models reports two separate fields: `accelerator` (what the engine is
        // actually running on -- modelRegistry.getEngineAccelerator, recorded by
        // reinitializeModel and left standing through this restore) and `preferred_accelerator`
        // (this restored, stored value). It is also why ensureAccelerator itself now compares a
        // later request's target against the engine's actual accelerator, not this restored
        // label -- see its doc comment in OpenAiServer.kt.
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
            // LlmChatModelHelper sets SamplerConfig to null on the NPU backend, so
            // temperature/top_p/top_k are silently ignored by the runtime there. Be honest
            // about it server-side rather than pretending the override took effect.
            Log.w(TAG, "Model '${model.name}' is running on $effectiveAccelLabel; " +
                "temperature/top_p/top_k are ignored by the runtime on this backend.")
        }

        try {
            request.temperature?.let { model.configValues = model.configValues + (ConfigKeys.TEMPERATURE.label to it) }
            request.top_p?.let { model.configValues = model.configValues + (ConfigKeys.TOPP.label to it) }
            request.top_k?.let { model.configValues = model.configValues + (ConfigKeys.TOPK.label to it) }
            request.max_tokens?.let { model.configValues = model.configValues + (ConfigKeys.MAX_TOKENS.label to it) }

            // F3: tool calling. LiteRT-LM's tool mechanism (com.google.ai.edge.gallery.tools.
            // ToolDefinition / ToolSet) is a set of compile-time, @Tool-annotated Kotlin
            // methods (LoadSkillTool, RunMcpTool, RunJsTool, RunIntentTool -- see
            // customtasks/agentchat/AgentTools.kt) that the runtime executes internally and
            // loops on; there is no path from an arbitrary per-request JSON function schema
            // (OpenAI's `tools`) to a dynamically constructed ToolSet, and runInference's
            // ResultListener never surfaces "model wants to call X with args Y" as an
            // inspectable event -- the tool-call loop is closed inside the runtime, not
            // exposed to a caller. So rather than silently ignoring `tools` (the pre-existing
            // behavior -- worse than an explicit error), refuse clearly.
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

            // Parse messages for system instruction and multi-turn context
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

            // F4: the current turn's image_url content parts. Only the LAST message's images
            // are ever sent to the runtime (matches runInference's single-turn `images`
            // param); images attached to earlier turns in history are dropped during replay
            // below, same limitation as assistant-message replay.
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
                // Build prior-turn history (everything except the final user message handled via
                // `prompt` below) as LiteRT-LM Message objects. Role mapping matches
                // DefaultLlmSessionManager.protoToLitertMessage.
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
                // openSession seats the conversation itself (empty history for a new id) using
                // the client's id verbatim -- no minted substitute, no new/existing branching.
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

    // See handleChatCompletion above for why NO_BUSY_GUARD_TIMEOUT_MS and
    // model.runtimeHelper.stopResponse(model) are used here.
    val guardResult = withBusyGuard(
        mutexes = modelMutexes,
        key = model.name,
        timeoutMs = NO_BUSY_GUARD_TIMEOUT_MS,
        onCancel = { model.runtimeHelper.stopResponse(model) },
    ) {
        // See handleChatCompletion for why this snapshot is taken before ensureAccelerator
        // (not after), what it restores in the finally below (the stored PREFERENCE, not the
        // engine's actual accelerator), and what it deliberately does not reconcile (the engine
        // can keep running on a per-request accelerator override after the recorded config
        // label is restored).
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

        try {
            request.temperature?.let { model.configValues = model.configValues + (ConfigKeys.TEMPERATURE.label to it) }
            request.top_p?.let { model.configValues = model.configValues + (ConfigKeys.TOPP.label to it) }
            request.top_k?.let { model.configValues = model.configValues + (ConfigKeys.TOPK.label to it) }
            request.max_tokens?.let { model.configValues = model.configValues + (ConfigKeys.MAX_TOKENS.label to it) }

            if (request.stream) {
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    val id = "cmpl-" + UUID.randomUUID().toString()
                    val created = System.currentTimeMillis() / 1000
                    collectInferenceStream(this, model, request.prompt) { text ->
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
                val responseText = collectInferenceText(model, request.prompt)
                call.respond(CompletionResponse(
                    id = "cmpl-" + UUID.randomUUID().toString(),
                    created = System.currentTimeMillis() / 1000,
                    model = model.name,
                    choices = listOf(
                        CompletionChoice(
                            index = 0,
                            text = responseText,
                            finish_reason = "stop"
                        )
                    )
                ))
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

// F3, EXPERIMENTAL: thin Anthropic-style /v1/messages translator over the same model
// lookup / mutex / NPU-guard / inference plumbing as handleChatCompletion, re-shaped to
// Anthropic's request/response fields. `system` is Anthropic's top-level field (not a
// message) -- handled explicitly below, not looked for in `messages`. Streaming (SSE with
// Anthropic's event names) is NOT implemented -- stream:true gets a plain 501, not a
// silently-ignored flag.
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
        // AnthropicMessagesRequest has no per-request accelerator field -- keep whatever the
        // model's currently configured accelerator is (same as passing null elsewhere).
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

    // See handleChatCompletion above for why NO_BUSY_GUARD_TIMEOUT_MS and
    // model.runtimeHelper.stopResponse(model) are used here.
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

        // See handleChatCompletion for why this snapshot/restore is here instead of a
        // direct, unrestored mutation of the shared model.configValues.
        val originalConfigValues = model.configValues
        try {
            model.configValues = model.configValues + (ConfigKeys.MAX_TOKENS.label to request.max_tokens)
            request.temperature?.let { model.configValues = model.configValues + (ConfigKeys.TEMPERATURE.label to it) }
            request.top_p?.let { model.configValues = model.configValues + (ConfigKeys.TOPP.label to it) }
            request.top_k?.let { model.configValues = model.configValues + (ConfigKeys.TOPK.label to it) }

            // Anthropic's `system` is top-level, not a message -- do NOT look for a
            // "system" role inside `messages`.
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
                // Build prior-turn history the same way handleChatCompletion does, so the model
                // sees its own previous replies -- not just the user's turns.
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
                // openSession seats the conversation itself (empty history for a new id) using
                // the client's id verbatim -- no minted substitute, no new/existing branching.
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

            val resultText = collectInferenceText(model, lastParsed.text, lastParsed.images)

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
                    stop_reason = "end_turn",
                    // Box: this runtime doesn't expose token counts through runInference's
                    // ResultListener, so usage is always reported as 0/0 rather than faked.
                    usage = AnthropicUsage(input_tokens = 0, output_tokens = 0),
                    session_id = effectiveSessionId,
                )
            )
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

// --- Inference wiring: uses the relay's per-runtime dispatch (model.runtimeHelper),
// not a hardcoded chat helper, so LiteRT-LM / llama.cpp / AICore models all work
// through the same API surface. ---

private suspend fun runInferenceBlocking(model: Model, prompt: String, images: List<Bitmap> = emptyList()): ChatCompletionResponse {
    val resultText = collectInferenceText(model, prompt, images)
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
        )
    )
}

// WP R2, Part B: events the resultListener/onError callbacks (invoked from the LiteRT-LM
// runtime's own callback thread, NOT the Ktor handler coroutine) hand off to the drain loop
// below. A Channel replaces the previous per-chunk `runBlocking { writer.write... }` -- that
// blocked the runtime's token-callback thread on slow client I/O, and a concurrent
// cancelProcess() (this refactor's own BusyGuard onCancel, or the UI's stop button) then had
// to wait behind it. trySend on an UNLIMITED channel never blocks, so the callback thread is
// never stalled by the network; the actual writer.writeStringUtf8/flush calls now happen on
// the handler coroutine that owns `writer`, which is where they belong.
private sealed class StreamEvent {
    data class Chunk(val text: String) : StreamEvent()
    object Done : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

// Shared by handleChatCompletion's and handleCompletion's streaming responses above -- the two
// previously differed only in which @Serializable chunk type (ChatCompletionChunk vs
// CompletionChunk) got built from each piece of text and written as one SSE `data:` line, plus
// the "chatcmpl-"/"cmpl-" id prefix. Both of those are caller-specific (the id/created pair is
// generated once by the caller, before this is invoked, and closed over by `encodeChunk`); the
// draining Channel, [DONE]/error framing, and flush ordering below are identical for both and
// live here once instead of twice.
private suspend fun collectInferenceStream(
    writer: ByteWriteChannel,
    model: Model,
    prompt: String,
    images: List<Bitmap> = emptyList(),
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

    // Draining coroutine: a client disconnect cancels this suspend fun (Ktor cancels the
    // respondBytesWriter coroutine), which throws CancellationException out of the `for`
    // loop's suspension point on events.receive() -- no thread is left blocked waiting on a
    // channel nobody will ever drain again, and the finally below still runs to close it.
    try {
        for (event in events) {
            when (event) {
                is StreamEvent.Chunk -> {
                    writer.writeStringUtf8("data: ${encodeChunk(event.text)}\n\n")
                    writer.flush()
                }
                is StreamEvent.Done -> {
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

