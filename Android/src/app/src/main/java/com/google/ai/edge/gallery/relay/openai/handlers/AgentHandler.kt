/*
 * Exposes the app's own built-in agent tools over the local API.
 *
 * Client-supplied tool calling is not possible on this runtime: LiteRT-LM only accepts
 * compile-time @Tool-annotated Kotlin classes reflected into a ToolSet, and
 * Conversation.sendMessageAsync's tool-call loop is closed inside the runtime -- it never
 * surfaces "the model wants to call X with args Y" as an inspectable event to a caller of
 * model.runtimeHelper.runInference.
 *
 * What this file does instead: let a client run the tools the app already ships --
 * customtasks/mobileactions/MobileActionsTools.kt, a litertlm ToolSet with @Tool-annotated
 * methods (flashlight, wifi/bluetooth/sound settings, dial, SMS-compose, calendar, contacts,
 * map, email, alarm, timer, URL). It reuses the exact wiring the in-app MobileActions task uses
 * (MobileActionsTask.kt: `tool(MobileActionsTools(onFunctionCalled = ...))` fed into
 * LlmChatModelHelper.resetConversation's `tools` param, then a normal runInference call), so the
 * automatic tool-calling loop runs exactly as it does for the UI. The ToolSet's own
 * onFunctionCalled callback (fires synchronously, from inside the runtime, whenever a tool
 * method actually executes) is the one hook available to observe/gate individual tool calls from
 * outside the closed loop, and is used here both for the audit log and for allowlist enforcement
 * below.
 *
 * SAFETY: these tools act on the user's real phone (send SMS, dial, write contacts/calendar,
 * open arbitrary URLs). Real execution of a tool's Action -- actually calling
 * MobileActionsViewModel.performAction(action, context), which fires the Android
 * Intent/CameraManager side effect -- only happens for tool names in the persisted allowlist
 * (OpenAiServerState.allowedTools, seeded from [DEFAULT_ALLOWED_TOOLS] the first time it's read
 * on a given install -- see [allowedToolsForApiKey]); everything else still gets a canned
 * "success" response fed back to the model (matching what MobileActionsTools' own @Tool methods
 * always return), but its real side effect is withheld, the step is marked blocked, and the
 * whole request is failed with 403 once the run completes.
 */
package com.google.ai.edge.gallery.relay.openai.handlers

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.customtasks.mobileactions.Action
import com.google.ai.edge.gallery.customtasks.mobileactions.MobileActionsTools
import com.google.ai.edge.gallery.customtasks.mobileactions.MobileActionsViewModel
import com.google.ai.edge.gallery.customtasks.mobileactions.ToolOutcome
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.openai.AgentRunRequest
import com.google.ai.edge.gallery.relay.openai.AgentRunResponse
import com.google.ai.edge.gallery.relay.openai.AgentStepData
import com.google.ai.edge.gallery.relay.openai.AgentToolData
import com.google.ai.edge.gallery.relay.openai.AgentToolsResponse
import com.google.ai.edge.gallery.relay.openai.ErrorBody
import com.google.ai.edge.gallery.relay.openai.ErrorEnvelope
import com.google.ai.edge.gallery.relay.openai.OpenAiServerState
import com.google.ai.edge.gallery.relay.modelmanager.ModelRegistry
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.tool
import com.google.gson.JsonArray as GsonArray
import com.google.gson.JsonElement as GsonElement
import com.google.gson.JsonObject as GsonObject
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray as KJsonArray
import kotlinx.serialization.json.JsonElement as KJsonElement
import kotlinx.serialization.json.JsonNull as KJsonNull
import kotlinx.serialization.json.JsonObject as KJsonObject
import kotlinx.serialization.json.JsonPrimitive as KJsonPrimitive

private const val TAG = "AGAgentHandler"

// Honoured when the client omits `max_steps`; hard cap regardless of what a client asks for
// (RECURRING_TOOL_CALL_LIMIT inside litertlm's Conversation is the ultimate backstop -- this is
// a lower, server-side one so a single run can't be used to fire an unbounded number of device
// actions).
private const val DEFAULT_MAX_STEPS = 5
private const val MAX_STEPS_CAP = 20

// Bounds a single /v1/agent/run request end-to-end (covers model generation + any tool
// invocations the model triggers along the way).
private const val AGENT_RUN_TIMEOUT_MS = 120_000L

/**
 * All 14 tool names (matching MobileActionsTools' method names /
 * Action.functionCallDetails.functionName) that can be given to an API client at all.
 */
internal val ALL_MOBILE_ACTION_TOOLS =
    listOf(
        "turnOnFlashlight",
        "turnOffFlashlight",
        "createContact",
        "sendEmail",
        "showLocationOnMap",
        "openWifiSettings",
        "createCalendarEvent",
        "setAlarm",
        "setTimer",
        "dialNumber",
        "sendSms",
        "openUrl",
        "openBluetoothSettings",
        "openSoundSettings",
    )

/**
 * Tools that open a compose/dial/browse surface with attacker-or-model-chosen content
 * (dialNumber, sendSms) or navigate to a model-chosen URL (openUrl -- phishing/exfil-via-
 * query-string risk). These must default OFF and stay off unless the user explicitly opts each
 * one in from the Server screen -- this project's standing rule for security-relevant toggles.
 * Not enforced here beyond documentation + the UI grouping (ServerScreen.kt): nothing in this
 * file refuses to persist a set that includes one of these, since the user IS allowed to opt in.
 * What must never happen is one of these appearing in [DEFAULT_ALLOWED_TOOLS] below.
 */
internal val RISKY_TOOLS = setOf("dialNumber", "sendSms", "openUrl")

/**
 * Seed value for the persisted allowlist (OpenAiServerState.loadAllowedTools) the first time a
 * given install reads it with nothing yet persisted -- keeps behaviour unchanged for an existing
 * install until the user visits the Server screen and changes something. None of [RISKY_TOOLS]
 * is in here.
 *
 * Default-on rule (read-only/benign only; anything that sends a message, places a call, or
 * writes user data is off by default):
 *  - turnOnFlashlight / turnOffFlashlight: toggles the torch, fully reversible, no data written.
 *  - openWifiSettings / openBluetoothSettings / openSoundSettings: opens a system Settings
 *    screen; doesn't change anything by itself.
 * Left off by default (user can opt in from the Server screen):
 *  - openUrl, showLocationOnMap: launches a browser/maps intent with attacker-or-model-chosen
 *    content (phishing/exfil-via-query-string risk).
 *  - dialNumber, sendSms, sendEmail: one tap/step from placing a call or sending a message.
 *  - createContact, createCalendarEvent, setAlarm, setTimer: write durable, user-visible data.
 */
internal val DEFAULT_ALLOWED_TOOLS =
    setOf(
        "turnOnFlashlight",
        "turnOffFlashlight",
        "openWifiSettings",
        "openBluetoothSettings",
        "openSoundSettings",
    )

// The allowlist is user-configurable, persisted in OpenAiServerState (same SharedPreferences
// store as bindMode etc.) rather than a hardcoded constant. `apiKey` stays an
// accepted-but-unused parameter: single-key deployment today (OpenAiServerState.apiKey() issues
// exactly one key), and the set is keyed globally, not per key -- see
// OpenAiServerState.loadAllowedTools/setAllowedTools. Keeping the parameter (rather than
// dropping it and renaming the function) preserves the existing indirection for a real per-key
// map to replace the body later without touching call sites.
private fun allowedToolsForApiKey(
    context: Context,
    @Suppress("UNUSED_PARAMETER") apiKey: String?,
): Set<String> = OpenAiServerState.loadAllowedTools(context, defaultIfUnset = DEFAULT_ALLOWED_TOOLS)

// litertlm's ReflectionTool emits snake_case tool names (e.g. "turn_on_flashlight") while
// DEFAULT_ALLOWED_TOOLS holds the camelCase Kotlin @Tool method names (e.g. "turnOnFlashlight"),
// so a plain `in` check against the raw names never matches. Normalise both sides (lowercase,
// strip underscores) before comparing rather than retyping DEFAULT_ALLOWED_TOOLS's casing, so
// this keeps working whichever casing litertlm emits.
private fun normalizeToolName(name: String): String = name.lowercase().replace("_", "")

private val NORMALIZED_DEFAULT_ALLOWED_TOOLS: Set<String> =
    DEFAULT_ALLOWED_TOOLS.map(::normalizeToolName).toSet()

// `allowedTools` now normally comes from the persisted allowlist (allowedToolsForApiKey ->
// OpenAiServerState.loadAllowedTools), not always DEFAULT_ALLOWED_TOOLS -- the `===` fast path
// below only fires on the one call site that still passes the constant directly (the never-yet-
// persisted case), everything else falls through to normalising `allowedTools` on the spot.
private fun isToolAllowed(toolName: String, allowedTools: Set<String>): Boolean =
    if (allowedTools === DEFAULT_ALLOWED_TOOLS) {
        normalizeToolName(toolName) in NORMALIZED_DEFAULT_ALLOWED_TOOLS
    } else {
        normalizeToolName(toolName) in allowedTools.map(::normalizeToolName).toSet()
    }

suspend fun handleAgentRun(
    call: ApplicationCall,
    request: AgentRunRequest,
    context: Context,
    modelRegistry: ModelRegistry,
    agentMutexes: ConcurrentHashMap<String, Mutex>,
    // Reuses OpenAiServer's private ensureAccelerator/NPU-guard logic (bound by the caller) --
    // null means "ok to proceed", a non-null String is the model name currently holding the NPU.
    ensureAccelerator: suspend (Model, Accelerator?) -> String?,
) {
    if (request.prompt.isBlank()) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorEnvelope(ErrorBody(message = "Missing required 'prompt' field")),
        )
        return
    }

    val allModels = modelRegistry.tasks.flatMap { it.models }
    val model =
        if (request.model != null) {
            allModels.find { it.name == request.model }
        } else {
            allModels.find { it.instance != null && it.llmSupportMobileActions }
        }

    if (model == null || model.instance == null) {
        call.respond(
            HttpStatusCode.NotFound,
            mapOf(
                "error" to
                    if (request.model != null) {
                        "Model '${request.model}' not found or not initialized"
                    } else {
                        "No model specified and no mobile-actions-capable model is currently loaded"
                    }
            ),
        )
        return
    }

    if (!model.llmSupportMobileActions) {
        call.respond(
            HttpStatusCode.NotImplemented,
            ErrorEnvelope(
                ErrorBody(
                    message =
                        "Model '${model.name}' cannot drive tools: this endpoint reuses the same " +
                            "runtime tool-calling loop as the in-app MobileActions task (LiteRT-LM " +
                            "automatic tool calling, closed inside Conversation.sendMessageAsync), " +
                            "which is only wired up for models with llmSupportMobileActions=true. " +
                            "GGUF/llama.cpp models and other function-calling-incapable models are " +
                            "not supported here -- same limitation as the 'tools' 501 on " +
                            "/v1/chat/completions.",
                    type = "not_implemented_error",
                )
            ),
        )
        return
    }

    val maxSteps = (request.max_steps ?: DEFAULT_MAX_STEPS).coerceIn(1, MAX_STEPS_CAP)

    // model.runtimeHelper.stopResponse(model) is the real cancel here (LlmModelHelper.stopResponse
    // -> instance.conversation.cancelProcess(), the same call LlmChatModelHelper.cleanUp() and the
    // UI's "stop" button use) -- it fires whenever this run doesn't finish normally, whether that
    // is the AGENT_RUN_TIMEOUT_MS timeout below or the client disconnecting mid-run, so a live
    // generation never keeps burning CPU with nobody left to receive it.
    val guardResult = withBusyGuard(
        mutexes = agentMutexes,
        key = model.name,
        timeoutMs = AGENT_RUN_TIMEOUT_MS,
        onCancel = { model.runtimeHelper.stopResponse(model) },
    ) {
        val heldBy = ensureAccelerator(model, null)
        if (heldBy != null) {
            call.respond(
                HttpStatusCode.TooManyRequests,
                ErrorEnvelope(
                    ErrorBody(
                        message =
                            "NPU is currently held by model '$heldBy'; only one model may use the " +
                                "NPU at a time on this device."
                    )
                ),
            )
            return@withBusyGuard
        }

        val apiKey = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        val allowedTools = allowedToolsForApiKey(context, apiKey)

        val steps = mutableListOf<AgentStepData>()
        var blockedTool: String? = null
        // Fresh, unmanaged instance -- MobileActionsViewModel's constructor only needs an
        // application Context, no Hilt graph required to call its public performAction(). This
        // IS the app's real execution path (Intent building / CameraManager torch toggling
        // lives there, private, and is intentionally not duplicated here).
        val actionsViewModel = MobileActionsViewModel(context)

        val toolSet =
            MobileActionsTools(
                onFunctionCalled = { action: Action ->
                    val toolName = action.functionCallDetails.functionName
                    val arguments = action.functionCallDetails.parameters.toMap()
                    // `result` (below) is only the audit-log string recorded into `steps` for
                    // this HTTP response's own JSON -- it is not what the model sees.
                    // MobileActionsTools builds the map it returns to the model from the
                    // ToolOutcome returned here, so a disallowed or failed call is reported to
                    // the model as refused/failed, not success.
                    val (result, outcome) =
                        when {
                            blockedTool != null ->
                                "skipped: run already blocked on tool '$blockedTool'" to
                                    ToolOutcome.Refused(
                                        toolName = toolName,
                                        reason = "an earlier tool call in this run was refused",
                                    )
                            !isToolAllowed(toolName, allowedTools) -> {
                                blockedTool = toolName
                                "blocked: '$toolName' is not allowlisted for this API key" to
                                    ToolOutcome.Refused(toolName = toolName)
                            }
                            steps.size >= maxSteps ->
                                "blocked: max_steps ($maxSteps) reached" to
                                    ToolOutcome.Refused(
                                        toolName = toolName,
                                        reason = "the step limit for this run has been reached",
                                    )
                            else -> {
                                // The real device-side effect. performAction already try/catches
                                // internally and returns an error string instead of throwing; it
                                // also unconditionally adds Intent.FLAG_ACTIVITY_NEW_TASK, so a
                                // failure surfaced via `sideEffectError` below is a real one (no
                                // target app installed, bad arguments, etc.), not a structural
                                // headless-Context limitation.
                                val sideEffectError = actionsViewModel.performAction(action, context)
                                if (sideEffectError.isEmpty()) {
                                    "ok" to ToolOutcome.Success
                                } else {
                                    "error: $sideEffectError" to ToolOutcome.Failed(sideEffectError)
                                }
                            }
                        }
                    Log.i(TAG, "agent tool invoked: name=$toolName arguments=$arguments result=$result")
                    steps.add(AgentStepData(tool = toolName, arguments = arguments, result = result))
                    outcome
                }
            )

        try {
            LlmChatModelHelper.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemInstruction = null,
                tools = listOf(tool(toolSet)),
            )

            // Bounded by withBusyGuard's outer timeoutMs (AGENT_RUN_TIMEOUT_MS) now -- a
            // TimeoutCancellationException here propagates out of block and is turned into the
            // same GatewayTimeout response by the BusyResult.TimedOut branch below.
            val answer = collectInferenceText(model, request.prompt)

            val blocked = blockedTool
            if (blocked != null) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorEnvelope(
                        ErrorBody(
                            message =
                                "Tool '$blocked' is not allowlisted for this API key. Allowed tools: " +
                                    "${allowedTools.joinToString(", ")}. Enable it from the app's " +
                                    "API Server screen (Agent tools section) to allow this run."
                        )
                    ),
                )
                return@withBusyGuard
            }

            call.respond(AgentRunResponse(answer = answer, steps = steps.toList(), model = model.name))
        } finally {
            // Leave the model wired back to no tools so the next /v1/chat/completions or
            // /v1/messages request against this same model instance isn't left with
            // MobileActions tools silently attached -- mirrors the config-value snapshot/restore
            // pattern OpenAiServer.handleChatCompletion uses for sampling overrides.
            try {
                LlmChatModelHelper.resetConversation(
                    model = model,
                    supportImage = false,
                    supportAudio = false,
                    systemInstruction = null,
                    tools = emptyList(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reset conversation back to no-tools state after agent run", e)
            }
        }
    }
    when (guardResult) {
        is BusyResult.Busy -> call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Model is busy"))
        is BusyResult.TimedOut -> call.respond(
            HttpStatusCode.GatewayTimeout,
            ErrorEnvelope(ErrorBody(message = "Agent run timed out after ${AGENT_RUN_TIMEOUT_MS / 1000}s")),
        )
        is BusyResult.Ok -> {}
    }
}

suspend fun handleAgentTools(call: ApplicationCall, context: Context) {
    // Reads through the exact same path handleAgentRun uses (allowedToolsForApiKey ->
    // OpenAiServerState.loadAllowedTools), not the in-memory StateFlow's last-seen value. This
    // matters because headless is the NORMAL mode here: a client can hit GET /v1/agent/tools on
    // a freshly booted server process before anyone has opened the Server screen or run an
    // agent, and OpenAiServerState.allowedTools would still be null (never loaded this process)
    // at that point -- falling back to DEFAULT_ALLOWED_TOOLS would silently misreport a
    // different persisted configuration. Reading from disk here (loadAllowedTools) makes both
    // endpoints answer from one source of truth: what /v1/agent/run will actually enforce.
    val apiKey = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
    val allowedTools = allowedToolsForApiKey(context, apiKey)

    // Reflects the real @Tool/@ToolParam annotations on MobileActionsTools via litertlm's own
    // ReflectionTool (same machinery MobileActionsTask.kt uses to wire the ToolSet into the
    // model) -- not a hand-maintained duplicate list that can drift from the real methods.
    val manager = ToolManager(listOf(tool(MobileActionsTools(onFunctionCalled = { ToolOutcome.Success }))))
    val descriptions: GsonArray = manager.getToolsDescription()

    val tools =
        descriptions.map { element ->
            val obj = element.asJsonObject
            val fn = obj.getAsJsonObject("function") ?: obj   // litertlm nests fields under "function"
            val name = fn.get("name")?.asString ?: ""
            AgentToolData(
                name = name,
                description = fn.get("description")?.asString ?: "",
                parameters = (fn.get("parameters") ?: GsonObject()).toKotlinxJson(),
                allowlisted = isToolAllowed(name, allowedTools),
            )
        }

    call.respond(AgentToolsResponse(tools = tools))
}

// Gson (litertlm's ReflectionTool output) -> kotlinx.serialization.json (this server's wire
// format) bridge. Purely a serialization-format conversion, not a re-derivation of the schema
// itself -- the schema's shape/content comes entirely from ReflectionTool.getToolDescription().
private fun GsonElement.toKotlinxJson(): KJsonElement =
    when {
        this.isJsonNull -> KJsonNull
        this.isJsonPrimitive -> {
            val primitive = this.asJsonPrimitive
            when {
                primitive.isBoolean -> KJsonPrimitive(primitive.asBoolean)
                primitive.isNumber -> KJsonPrimitive(primitive.asNumber)
                else -> KJsonPrimitive(primitive.asString)
            }
        }
        this.isJsonArray -> KJsonArray(this.asJsonArray.map { it.toKotlinxJson() })
        this.isJsonObject ->
            KJsonObject(this.asJsonObject.entrySet().associate { (k, v) -> k to v.toKotlinxJson() })
        else -> KJsonNull
    }
