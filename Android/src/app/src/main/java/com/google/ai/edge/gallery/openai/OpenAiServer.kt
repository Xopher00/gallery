/*
 * Ported from mobile-server (com.server.edge.gallery) into the Gallery API-server fork.
 *
 * Changes from the source:
 *  - package rewritten to com.google.ai.edge.gallery
 *  - binds to 127.0.0.1 only (was 0.0.0.0)
 *  - inference now goes through model.runtimeHelper.runInference (fork's per-runtime
 *    dispatch: LiteRT-LM / llama.cpp / AICore) instead of a hardcoded LlmChatModelHelper
 *  - bearer API key auth required on all v1 routes; health is open
 */
package com.google.ai.edge.gallery.openai

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.openai.handlers.NO_BUSY_GUARD_TIMEOUT_MS
import com.google.ai.edge.gallery.openai.handlers.ON_DEMAND_MODEL_LOAD_TIMEOUT_MS
import com.google.ai.edge.gallery.openai.handlers.BusyResult
import com.google.ai.edge.gallery.openai.handlers.handleAgentRun
import com.google.ai.edge.gallery.openai.handlers.handleAgentTools
import com.google.ai.edge.gallery.openai.handlers.handleAnthropicMessages
import com.google.ai.edge.gallery.openai.handlers.handleAudioTranscriptions
import com.google.ai.edge.gallery.openai.handlers.handleChatCompletion
import com.google.ai.edge.gallery.openai.handlers.handleCompletion
import com.google.ai.edge.gallery.openai.handlers.handleImageEdits
import com.google.ai.edge.gallery.openai.handlers.handleImageGenerations
import com.google.ai.edge.gallery.openai.handlers.handleOcr
import com.google.ai.edge.gallery.openai.handlers.handleVisionDetect
import com.google.ai.edge.gallery.openai.handlers.handleVisionSegment
import com.google.ai.edge.gallery.openai.handlers.withBusyGuard
import com.google.ai.edge.gallery.modelmanager.ModelRegistry
import com.google.ai.edge.gallery.runtime.LlamaCppModelHelper
import com.google.ai.edge.gallery.runtime.aicore.AICoreModelHelper
import com.google.ai.edge.gallery.runtime.runtimeHelper
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private const val TAG = "AGOpenAiServer"

// taskId passed to LlmModelHelper.initialize() for API-driven (re)initializations. Only used
// by the runtime to gate task-scoped capabilities (e.g. speculative decoding); a stable
// constant is fine since the OpenAI API surface doesn't have its own task concept.
private const val API_TASK_ID = "openai_api"

// Outcome of OpenAiServer.loadModel(), mapped to HTTP status codes by the
// POST /v1/models/{id}/load route and by the on-demand load call sites in ChatHandler/
// ImageGenerationHandler/AudioTranscriptionHandler. Kept top-level (not nested in OpenAiServer)
// so those handler files -- which take a `loadModel` function reference rather than an
// OpenAiServer instance -- can reference the type.
sealed class LoadResult {
    data class Loaded(val name: String, val accelerator: String) : LoadResult()
    data class NotFound(val message: String) : LoadResult()
    data class Busy(val message: String) : LoadResult()
    data class Conflict(val message: String) : LoadResult()
    data class Error(val message: String) : LoadResult()
    data class TimedOut(val message: String) : LoadResult()
}

// Which single-holder "engine kind" a model belongs to, for the one-pinned-model-per-kind
// rule in loadModel(). Derived from the model's containing Task id (BuiltInTaskId) rather than
// from model.instance's runtime type, because this must work before the model is initialized
// (instance is still null) -- the exact case loadModel exists to handle. Mirrors the same
// instance-type split ImageGenerationHandler/AudioTranscriptionHandler use post-init
// (StableDiffusion/WhisperEngine/everything else = LLM).
private enum class EngineKind { LLM, STABLE_DIFFUSION, WHISPER }

private fun engineKindForTask(taskId: String): EngineKind = when (taskId) {
    BuiltInTaskId.IMAGE_GEN -> EngineKind.STABLE_DIFFUSION
    BuiltInTaskId.WHISPER -> EngineKind.WHISPER
    else -> EngineKind.LLM
}

// What ConfigKeys.ACCELERATOR actually falls back to for a model that has never had the key
// set. Model.preProcess() seeds configValues[ACCELERATOR] from the ACCELERATOR SegmentedButtonConfig
// built by createLlmChatConfigs(accelerators = ...), whose defaultValue is accelerators[0].label --
// the first entry of the same compatible-accelerators list surfaced as Model.accelerators
// (ConfigKeys.COMPATIBLE_ACCELERATORS). preProcess() runs on every model before this server can
// see it, so ConfigKeys.ACCELERATOR is normally already present; Accelerator.GPU is only a last
// resort for the degenerate case of a model with an empty accelerators list. Not internal/private
// so openai.handlers.ChatHandler can use the same fallback instead of hardcoding GPU again.
fun honestDefaultAcceleratorLabel(model: Model): String =
    model.accelerators.firstOrNull()?.label ?: Accelerator.GPU.label

// SECURITY: normalises a raw request path once, for both the public-allowlist comparison and
// any logging. Collapses repeated slashes ("//v1/models"), resolves "."/".." segments
// ("/..//v1/models", "/./v1/models"), and strips a trailing slash ("/health/") -- without ever
// letting ".." pop above the root. This does not relax routing (Ktor's own router still decides
// what handles a request); it only decides what the auth interceptor below treats as "/health"
// for the public allowlist. Every path that does not resolve to exactly "/health" requires a
// valid bearer key -- deny-by-default, replacing a previous startsWith("/v1/") allow-by-prefix
// check that a doubled leading slash could slip past.
private fun normalizePath(rawPath: String): String {
    val collapsed = rawPath.replace(Regex("/+"), "/")
    val resolved = ArrayDeque<String>()
    for (segment in collapsed.split("/")) {
        when (segment) {
            "", "." -> {} // drop empty segments (leading/trailing/collapsed-slash artifacts) and "."
            ".." -> if (resolved.isNotEmpty()) resolved.removeLast() // never pop above root
            else -> resolved.addLast(segment)
        }
    }
    return "/" + resolved.joinToString("/")
}

// SECURITY: extracts the bearer token from a raw Authorization header, requiring the RFC 7235
// "Bearer" scheme (case-insensitive) rather than accepting a raw key with no scheme. A previous
// removePrefix("Bearer ") was a silent no-op when the prefix was absent, so a bare key in the
// header would have been accepted. Returns null for a missing header, a non-Bearer scheme, or an
// empty token.
private fun extractBearerToken(header: String?): String? {
    if (header == null) return null
    val spaceIdx = header.indexOf(' ')
    if (spaceIdx <= 0) return null
    val scheme = header.substring(0, spaceIdx)
    if (!scheme.equals("Bearer", ignoreCase = true)) return null
    val token = header.substring(spaceIdx + 1).trim()
    return token.ifEmpty { null }
}

// SECURITY: constant-time comparison so response timing can't leak how many leading bytes of a
// guessed key matched. MessageDigest.isEqual is documented time-constant regardless of an early
// length mismatch -- no new dependency, it's JDK-provided.
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

class OpenAiServer(
    private val context: Context,
    private val modelRegistry: ModelRegistry
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val modelMutexes = ConcurrentHashMap<String, Mutex>()

    // Set inside start() from the values actually used to bind/authenticate this running
    // server -- not re-derived from prefs, which may have changed since. checkConfig()
    // compares these against the current prefs to decide whether a rebind is needed.
    var boundHost: String = ""
        private set
    var apiKeyFingerprint: String = ""
        private set

    // Separate per-model mutex maps for the Whisper / Stable Diffusion endpoints below -- these
    // engines are unrelated to the LLM chat/completion models above (different instance types,
    // no NPU-slot involvement), so they get their own busy-guards rather than sharing
    // modelMutexes.
    private val transcriptionMutexes = ConcurrentHashMap<String, Mutex>()
    private val imageGenMutexes = ConcurrentHashMap<String, Mutex>()

    // MediaPipe tasks-vision endpoints (object detection/segmentation) use the same
    // per-model-name busy-guard pattern as above, in a separate map since these are yet another
    // unrelated instance type (vision/ObjectDetectorWrapper, vision/ImageSegmenterWrapper).
    private val visionDetectMutexes = ConcurrentHashMap<String, Mutex>()
    private val visionSegmentMutexes = ConcurrentHashMap<String, Mutex>()

    // /v1/agent/run uses one busy-guard per model name, the same pattern as modelMutexes above.
    // Kept in a separate map because an agent run reconfigures the model's conversation with
    // MobileActionsTools' ToolSet for its duration and restores it afterwards; a separate map
    // makes that lifecycle easier to reason about independently of chat/completions traffic on
    // the same model.
    private val agentMutexes = ConcurrentHashMap<String, Mutex>()

    // Bundled ML Kit OCR (vision/OcrEngine.kt) uses a single fixed-key busy-guard, since there
    // is only one recognizer, unlike the per-model-name maps above -- kept in a separate map for
    // the same unrelated-instance-type reason as above.
    private val ocrMutexes = ConcurrentHashMap<String, Mutex>()

    // Box: process-wide NPU single-slot guard. The Qualcomm CDSP domain accepts only one
    // process/model on the Hexagon NPU at a time (verified hardware behaviour) -- Accelerator.TPU
    // also maps to Backend.NPU in LlmChatModelHelper, so it shares the same physical slot.
    // Guarded by npuGuardMutex, separate from the per-model mutexes below so that two different
    // models' requests can't race the check-and-claim.
    private val npuGuardMutex = Mutex()
    @Volatile private var npuHolderModelName: String? = null

    private sealed class AcceleratorResult {
        object Ok : AcceleratorResult()
        data class Conflict(val heldBy: String) : AcceleratorResult()
    }

    private fun usesNpuSlot(accelerator: Accelerator) =
        accelerator == Accelerator.NPU || accelerator == Accelerator.TPU

    private fun parseAccelerator(raw: String): Accelerator? =
        Accelerator.values().find { it.label.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }

    // Ensures `model` is initialized against `requestedRaw` (or its current accelerator if
    // null), reinitializing under the model's own mutex only when the accelerator actually
    // changes, and enforcing the NPU single-slot rule. Must be called from inside
    // modelMutexes[model.name].withLock.
    //
    // "Current" here means what the engine is ACTUALLY running on (modelRegistry.
    // getEngineAccelerator), not the stored ConfigKeys.ACCELERATOR preference -- ChatHandler's
    // per-request accelerator override restores that stored value in a `finally` after the
    // request completes, so it can lag the engine's real state. Comparing against the stored
    // value instead would reintroduce that same bug in a new form here: a later request asking
    // for the accelerator the engine is already running on would see a stale "different" label
    // and trigger a pointless reinit, or a request asking for a genuinely different accelerator
    // could see a stale "same" label and be wrongly treated as a no-op. When there is no live
    // instance yet, there is no engine state to read, so this falls back to the stored
    // preference (or the model's honest default) the same way it always did.
    private suspend fun ensureAccelerator(model: Model, requestedRaw: Accelerator?): AcceleratorResult {
        val currentLabel = (if (model.instance != null) modelRegistry.getEngineAccelerator(model.name) else null)
            ?: model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = honestDefaultAcceleratorLabel(model))
        val currentAccel = parseAccelerator(currentLabel) ?: Accelerator.GPU
        val requested = requestedRaw ?: currentAccel
        // Also reinitializes when the model has no live instance at all -- loadModel() calls
        // this on a never-initialized model with requestedRaw possibly null (meaning "keep the
        // current/default accelerator"), where `requested != currentAccel` alone would be false
        // and this would wrongly skip initialization. Every other caller of ensureAccelerator
        // only reaches it after already checking model.instance != null, so this is a no-op
        // change for them.
        val needsReinit = requested != currentAccel || model.instance == null

        npuGuardMutex.withLock {
            if (usesNpuSlot(requested)) {
                val holder = npuHolderModelName
                if (holder != null && holder != model.name) {
                    return AcceleratorResult.Conflict(holder)
                }
                npuHolderModelName = model.name
            } else if (npuHolderModelName == model.name) {
                npuHolderModelName = null
            }
        }

        if (needsReinit) {
            try {
                reinitializeModel(model, requested)
            } catch (e: Exception) {
                if (usesNpuSlot(requested)) {
                    npuGuardMutex.withLock {
                        if (npuHolderModelName == model.name) npuHolderModelName = null
                    }
                }
                throw e
            }
        }
        return AcceleratorResult.Ok
    }

    // Tears down and recreates the model's runtime instance under the new accelerator. This
    // always mutates model.configValues[ACCELERATOR] -- it does not know or care whether its
    // caller wants that durable. POST /v1/models/{id}/load (via loadModel()) wants exactly that:
    // the accelerator it initializes onto becomes the model's recorded setting. A per-request
    // accelerator override on chat/completions/completions is different: ChatHandler calls
    // ensureAccelerator (which calls this) directly, and snapshots model.configValues before
    // that call and restores it in a finally after the request, so the mutation this function
    // makes does not outlive that one request. See ChatHandler.kt's handleChatCompletion/
    // handleCompletion for that restore.
    //
    // Regardless of which caller it is, a successful reinit here always records `accelerator`
    // in modelRegistry as the engine's actual accelerator (modelRegistry.recordEngineAccelerator)
    // -- that record is never restored/rolled back the way model.configValues can be, because it
    // is meant to keep tracking whatever the engine is really doing, including for a per-request
    // override whose config restore is deliberately NOT accompanied by a reinit back (see the
    // comment on ChatHandler's snapshot/restore for why that reinit-back doesn't happen).
    private suspend fun reinitializeModel(model: Model, accelerator: Accelerator) {
        // LlmChatModelHelper.initialize() (the only implementation reachable from here) reads
        // the accelerator to init on directly off model.configValues[ConfigKeys.ACCELERATOR] --
        // there is no separate parameter for the requested accelerator, so the new value must be
        // in configValues during the initialize() call below. It must not survive a failed init,
        // though: on failure this rolls back to `previousAcceleratorLabel` before throwing, so a
        // re-init that never actually came up under `accelerator` never leaves the model's
        // durable config pointing at a backend it isn't running on.
        val previousAcceleratorLabel = model.getStringConfigValue(
            key = ConfigKeys.ACCELERATOR,
            defaultValue = honestDefaultAcceleratorLabel(model),
        )
        model.configValues = model.configValues + (ConfigKeys.ACCELERATOR.label to accelerator.label)

        if (model.instance != null) {
            val cleanupDone = CompletableDeferred<Unit>()
            model.runtimeHelper.cleanUp(model) { cleanupDone.complete(Unit) }
            cleanupDone.await()
        }

        val initError = CompletableDeferred<String>()
        model.runtimeHelper.initialize(
            context = context,
            model = model,
            taskId = API_TASK_ID,
            supportImage = false,
            supportAudio = false,
            onDone = { errorMsg -> initError.complete(errorMsg) },
        )
        val error = initError.await()
        if (error.isNotEmpty()) {
            // Roll back the durable accelerator value (see comment above) and unpin: a pinned
            // model with model.instance == null (cleanUp() above already tore the old instance
            // down) would otherwise wedge every future UI open of it behind the full
            // CLEANUP_AWAIT_TIMEOUT_MS (60s) in ModelRegistry.cleanupModel, forever, since
            // nothing left would ever unpin it.
            model.configValues =
                model.configValues + (ConfigKeys.ACCELERATOR.label to previousAcceleratorLabel)
            // Also pass context so a failed reinit clears this model as the persisted boot-
            // preload target if it was pinned for one -- a boot should not retry a configuration
            // known to have just failed.
            OpenAiServerState.unpin(model.name, context)
            throw IllegalStateException(
                "Failed to reinitialize model '${model.name}' on ${accelerator.label}: $error"
            )
        }
        // The engine is now actually running on `accelerator` -- record it as such regardless
        // of whether model.configValues[ACCELERATOR] above is about to be restored by a caller
        // (see the doc comment on this function).
        modelRegistry.recordEngineAccelerator(model.name, accelerator.label)
    }

    // Resolves `name` against every task's model list (not just initialized ones), initializes
    // it via the existing ensureAccelerator/reinitializeModel path if needed, and pins it
    // (OpenAiServerState.pin) so ModelManagerViewModel.cleanupModel no-ops for it until
    // unloadModel()/server-stop unpins it again. Called both from the POST /v1/models/{id}/load
    // route and on-demand from ChatHandler/ImageGenerationHandler/AudioTranscriptionHandler when
    // a request names a model that isn't loaded yet. `timeoutMs` bounds how long a caller waits
    // for this specific load: it defaults to NO_BUSY_GUARD_TIMEOUT_MS (unbounded), which
    // POST /v1/models/{id}/load keeps using; the on-demand call sites pass
    // ON_DEMAND_MODEL_LOAD_TIMEOUT_MS instead, so an HTTP request that triggers a model load
    // implicitly can no longer wait forever on native init.
    suspend fun loadModel(
        name: String,
        accelerator: String?,
        timeoutMs: Long = NO_BUSY_GUARD_TIMEOUT_MS,
    ): LoadResult {
        val tasks = modelRegistry.tasks
        val task = tasks.find { t -> t.models.any { it.name == name } }
        val model = task?.models?.find { it.name == name }
        if (task == null || model == null) {
            return LoadResult.NotFound("Unknown model '$name'")
        }

        if (model.instance != null) {
            // Already loaded (by the UI, or a previous API call) -- pin it and report success
            // rather than re-triggering initialization.
            // Persist (name, accelerator) as the boot-preload target.
            OpenAiServerState.pin(name, context, currentAcceleratorLabel(model))
            return LoadResult.Loaded(name, currentAcceleratorLabel(model))
        }

        val downloaded = modelRegistry.getModelDownloadStatus(model).status ==
            ModelDownloadStatusType.SUCCEEDED
        if (!downloaded) {
            return LoadResult.NotFound("Model '$name' is not downloaded")
        }

        // One pinned model per engine kind: refuse a second LLM/StableDiffusion/Whisper load
        // while another model of the same kind is still pinned, rather than silently evicting it.
        val kind = engineKindForTask(task.id)
        val conflicting = OpenAiServerState.pinnedModels.value
            .filter { it != name }
            .firstOrNull { pinnedName ->
                val pinnedTask = tasks.find { t -> t.models.any { it.name == pinnedName } }
                pinnedTask != null && engineKindForTask(pinnedTask.id) == kind
            }
        if (conflicting != null) {
            return LoadResult.Conflict("Unload '$conflicting' first")
        }

        val requestedAccel = accelerator?.let { raw ->
            parseAccelerator(raw) ?: return LoadResult.Error(
                "Invalid accelerator '$raw'. Valid values: " +
                    Accelerator.values().joinToString(", ") { it.label.lowercase() }
            )
        }

        // StableDiffusion/Whisper are CPU-only engines -- their models still carry a recorded
        // ConfigKeys.ACCELERATOR value (defaulting to GPU) even though nothing reads it for real
        // backend selection on this kind. An explicit accelerator request for one of these that
        // isn't CPU can never be honoured -- fail with a clear 400 here rather than letting it
        // reach reinitializeModel()'s LLM-only LiteRT-LM init path and fail with an opaque 500
        // deep inside the engine.
        if (requestedAccel != null && kind != EngineKind.LLM && requestedAccel != Accelerator.CPU) {
            return LoadResult.Error(
                "Model '$name' uses a CPU-only ${kind.name.lowercase()} engine and does not " +
                    "support accelerator '${requestedAccel.label.lowercase()}'."
            )
        }

        // Same per-model busy-guard (atomic tryLock, no TOCTOU window) every other route uses --
        // a second request racing to load/use the same model while this init is in flight gets
        // BusyResult.Busy immediately instead of queueing behind it.
        val guardResult = withBusyGuard(
            mutexes = modelMutexes,
            key = model.name,
            timeoutMs = timeoutMs,
        ) {
            if (model.instance != null) {
                LoadResult.Loaded(name, currentAcceleratorLabel(model)) as LoadResult
            } else if (requestedAccel == null && kind != EngineKind.LLM) {
                // Caller did not explicitly ask for an accelerator, and this is a CPU-only
                // engine kind: initialise it the way the app's own UI does (the task's
                // initializeModelFn, e.g. WhisperTask/ImageGenTask directly constructing
                // WhisperEngine/StableDiffusion) instead of forcing the recorded (often stale
                // "gpu" default) accelerator through the LLM-only ensureAccelerator/
                // reinitializeModel path.
                val initError = CompletableDeferred<String?>()
                modelRegistry.initializeModel(
                    context = context,
                    task = task,
                    model = model,
                    onDone = { initError.complete(null) },
                    onError = { err -> initError.complete(err) },
                )
                val error = initError.await()
                if (error != null || model.instance == null) {
                    LoadResult.Error(
                        "Failed to initialize model '$name': ${error ?: "unknown error"}"
                    ) as LoadResult
                } else {
                    // Persist (name, accelerator) as the boot-preload target.
                    OpenAiServerState.pin(name, context, currentAcceleratorLabel(model))
                    LoadResult.Loaded(name, currentAcceleratorLabel(model)) as LoadResult
                }
            } else {
                when (val result = ensureAccelerator(model, requestedAccel)) {
                    is AcceleratorResult.Conflict -> LoadResult.Busy(
                        "NPU is currently held by model '${result.heldBy}'; only one model " +
                            "may use the NPU at a time on this device."
                    ) as LoadResult
                    is AcceleratorResult.Ok -> {
                        // Persist (name, accelerator) as the boot-preload target.
                        OpenAiServerState.pin(name, context, currentAcceleratorLabel(model))
                        LoadResult.Loaded(name, currentAcceleratorLabel(model)) as LoadResult
                    }
                }
            }
        }
        return when (guardResult) {
            is BusyResult.Ok -> guardResult.value
            is BusyResult.Busy -> LoadResult.Busy("Model '$name' is busy loading; try again.")
            is BusyResult.TimedOut -> LoadResult.TimedOut(
                "Timed out loading model '$name': load did not finish within " +
                    "${timeoutMs / 60_000}m."
            )
        }
    }

    private fun currentAcceleratorLabel(model: Model): String =
        model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = honestDefaultAcceleratorLabel(model))

    // Unpins `name` first (so cleanupModel's pin guard doesn't just no-op this) then routes
    // through the app's single teardown chokepoint, same as the UI's navigate-up.
    suspend fun unloadModel(name: String) {
        // Explicit unload also clears the persisted "last pinned" boot target if it matches, so
        // a rebooted phone doesn't reload a model the client just deliberately unloaded.
        OpenAiServerState.unpin(name, context)
        val task = modelRegistry.tasks.find { t -> t.models.any { it.name == name } }
            ?: return
        val model = task.models.find { it.name == name } ?: return
        modelRegistry.cleanupModel(context = context, task = task, model = model)
    }

    // Outcome of comparing this running server's actual bind host/API key against the current
    // prefs. Split into three cases (rather than a Boolean) so the caller can tell "config
    // genuinely changed, rebind" apart from "bindHost() throws right now" (e.g. INTERFACE mode's
    // selected network is transiently down) -- the latter must not be treated as a signal to
    // tear down an otherwise-healthy running server.
    sealed class ConfigCheck {
        object Matches : ConfigCheck()
        object NeedsRebind : ConfigCheck()
        data class BindUnavailable(val message: String) : ConfigCheck()
    }

    // Wraps bindHost() in try/catch since it throws for INTERFACE mode when the selected
    // interface isn't ready. Previously that throw was swallowed into "does not match", which
    // made the caller stop a running server over a transient interface blip -- now it is
    // reported as BindUnavailable instead, and bindHost() itself already records the reason in
    // OpenAiServerState.bindError (set on throw, cleared on the next successful bind).
    fun checkConfig(context: Context): ConfigCheck {
        val currentHost = try {
            OpenAiServerState.bindHost()
        } catch (e: Exception) {
            return ConfigCheck.BindUnavailable(e.message ?: "Failed to determine bind host")
        }
        return if (boundHost == currentHost &&
            apiKeyFingerprint == OpenAiServerState.fingerprint(OpenAiServerState.apiKey(context))
        ) {
            ConfigCheck.Matches
        } else {
            ConfigCheck.NeedsRebind
        }
    }

    fun start(port: Int = OpenAiServerState.DEFAULT_PORT) {
        if (server != null) return

        val apiKey = OpenAiServerState.apiKey(context)
        val host = OpenAiServerState.bindHost()
        boundHost = host
        apiKeyFingerprint = OpenAiServerState.fingerprint(apiKey)

        server = embeddedServer(Netty, port = port, host = host) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                    // kotlinx.serialization drops any field whose value equals its declared
                    // default -- e.g. ModelData.status defaults to "available", so an unloaded
                    // model's (correctly-set, non-null) "available" status was silently omitted
                    // from the wire response while "loaded" (which differs from the default)
                    // came through. encodeDefaults makes every explicitly-constructed field
                    // (status included) always serialize.
                    encodeDefaults = true
                })
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
            }

            // SECURITY: deny-by-default. Every request is authenticated except an exact-match
            // (post-normalization) public allowlist -- today just "/health". This replaces a
            // previous startsWith("/v1/") allow-by-prefix check, which a doubled leading slash
            // ("//v1/models") could bypass while Ktor's router still matched and ran the handler
            // -- a full unauthenticated bypass of model list, tool list, model unload,
            // chat/completions and agent/run. Because this runs for every path, including ones
            // no route matches, an unauthenticated request to an unknown path gets 401 here
            // rather than reaching routing and revealing a 404 (which would leak which routes
            // exist).
            intercept(ApplicationCallPipeline.Plugins) {
                val normalizedPath = normalizePath(call.request.path())
                if (normalizedPath == "/health") {
                    return@intercept
                }
                val token = extractBearerToken(call.request.headers[HttpHeaders.Authorization])
                if (token == null || !constantTimeEquals(token, apiKey)) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorEnvelope(ErrorBody(message = "Invalid or missing API key"))
                    )
                    finish()
                }
            }

            // On-demand loads triggered implicitly by a request naming an unloaded model
            // (chat/completions, image generations, audio transcriptions below) get a bounded
            // timeout so the HTTP request can't hang forever on native init -- unlike the
            // explicit POST /v1/models/{id}/load route further down, which keeps calling
            // loadModel() with its unbounded default.
            val onDemandLoadModel: suspend (String, String?) -> LoadResult = { name, accel ->
                loadModel(name, accel, ON_DEMAND_MODEL_LOAD_TIMEOUT_MS)
            }

            routing {
                get("/health") {
                    call.respond(
                        mapOf(
                            "status" to "ok",
                            "bind" to "$boundHost:$port",
                            "key" to apiKeyFingerprint,
                        )
                    )
                }

                // Lists every downloaded model (not just currently-initialized ones), each
                // tagged with status "loaded"/"available" -- a client can discover a model
                // exists and load it via POST .../load without the app UI ever having opened it.
                get("/v1/models") {
                    val models = modelRegistry.tasks
                        .flatMap { it.models }
                        .filter { modelRegistry.getModelDownloadStatus(it).status == ModelDownloadStatusType.SUCCEEDED }
                        .distinctBy { it.name }
                        .map { it.toModelData() }

                    call.respond(ModelsListResponse(data = models))
                }

                get("/v1/models/{modelId}") {
                    val modelId = call.parameters["modelId"]
                    val model = modelRegistry.tasks
                        .flatMap { it.models }
                        .find { it.name == modelId && modelRegistry.getModelDownloadStatus(it).status == ModelDownloadStatusType.SUCCEEDED }

                    if (model == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Model not found or not downloaded"))
                    } else {
                        call.respond(model.toModelData())
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
                    when (val result = loadModel(id, request.accelerator)) {
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
                            HttpStatusCode.InsufficientStorage, ErrorEnvelope(ErrorBody(message = result.message))
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
                    unloadModel(id)
                    call.respond(mapOf("id" to id, "status" to "unloaded"))
                }

                post("/v1/chat/completions") {
                    val request = call.receive<ChatCompletionRequest>()
                    handleChatCompletion(
                        call = call,
                        request = request,
                        modelRegistry = modelRegistry,
                        modelMutexes = modelMutexes,
                        parseAccelerator = ::parseAccelerator,
                        usesNpuSlot = ::usesNpuSlot,
                        ensureAccelerator = { model, accel ->
                            when (val result = ensureAccelerator(model, accel)) {
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
                        modelRegistry = modelRegistry,
                        modelMutexes = modelMutexes,
                        parseAccelerator = ::parseAccelerator,
                        usesNpuSlot = ::usesNpuSlot,
                        ensureAccelerator = { model, accel ->
                            when (val result = ensureAccelerator(model, accel)) {
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
                        modelRegistry = modelRegistry,
                        modelMutexes = modelMutexes,
                        ensureAccelerator = { model, accel ->
                            when (val result = ensureAccelerator(model, accel)) {
                                is AcceleratorResult.Ok -> null
                                is AcceleratorResult.Conflict -> result.heldBy
                            }
                        },
                        loadModel = onDemandLoadModel,
                    )
                }

                post("/v1/audio/transcriptions") {
                    handleAudioTranscriptions(call, context, modelRegistry, transcriptionMutexes, onDemandLoadModel)
                }

                post("/v1/images/generations") {
                    handleImageGenerations(call, modelRegistry, imageGenMutexes, onDemandLoadModel)
                }

                // MediaPipe tasks-vision (GPU delegate, CPU fallback -- no raw .tflite/QNN
                // stack). Models are Google first-party MediaPipe downloads, not bundled in the
                // APK; see vision/ModelCatalog.kt for expected paths and the 503 "not downloaded"
                // message these return until a file is present.
                post("/v1/vision/detect") {
                    handleVisionDetect(call, context, visionDetectMutexes)
                }

                post("/v1/vision/segment") {
                    handleVisionSegment(call, context, visionSegmentMutexes)
                }

                // Bundled ML Kit text recognition (no Play Services required at runtime, Latin
                // script only). Unlike the two routes above, the model is statically linked into
                // the APK, so there is no 503 "not downloaded" case here.
                post("/v1/vision/ocr") {
                    handleOcr(call, ocrMutexes)
                }

                // Honest 501: SD image editing is CPU-only/minutes-per-image on this device, so
                // it's not served over HTTP. See handleImageEdits for the message.
                post("/v1/images/edits") {
                    handleImageEdits(call)
                }

                // Runs the app's own built-in MobileActions tools (flashlight, wifi/bluetooth/
                // sound settings, dial, SMS-compose, etc. -- see MobileActionsTools.kt)
                // headlessly, driven by an API client's prompt. This is not client-supplied tool
                // calling (that stays a 501, see the `tools` handling in handleChatCompletion
                // above) -- the tool set is fixed and compiled in; per-API-key allowlisting and
                // audit logging live in AgentHandler.
                post("/v1/agent/run") {
                    val request = call.receive<AgentRunRequest>()
                    handleAgentRun(
                        call = call,
                        request = request,
                        context = context,
                        modelRegistry = modelRegistry,
                        agentMutexes = agentMutexes,
                        ensureAccelerator = { model, accel ->
                            when (val result = ensureAccelerator(model, accel)) {
                                is AcceleratorResult.Ok -> null
                                is AcceleratorResult.Conflict -> result.heldBy
                            }
                        },
                    )
                }

                get("/v1/agent/tools") {
                    handleAgentTools(call, context)
                }
            }
        }.start(wait = false)
        // Publish this instance so MainActivity's headless `--es load_model` extra (and any
        // other caller outside the Ktor routing lambdas) can reach loadModel()/unloadModel().
        OpenAiServerState.runningServer = this
        Log.i(TAG, "OpenAI API Server started on $boundHost:$port")
    }

    // Builds the OpenAI-shaped model DTO, surfacing the runtime this model actually loaded
    // under and its configured accelerators so a client can tell what it is getting.
    private fun Model.toModelData(): ModelData {
        // Box: report the engine that will ACTUALLY serve this model, not the (often stale)
        // RuntimeType enum -- ModelManagerViewModel hardcodes RuntimeType.LITERT_LM for every
        // imported model, GGUF included. `runtimeHelper` (runtime/ModelHelperExt.kt) is the
        // single source of truth for this dispatch decision (AICore vs. llama.cpp vs. LiteRT,
        // including imports-dir filename normalisation), so mirror it via identity check
        // instead of re-deriving the decision here.
        val runtime = when (this.runtimeHelper) {
            AICoreModelHelper -> "aicore"
            LlamaCppModelHelper -> "llama_cpp"
            else -> "litert_lm"
        }
        // What ConfigKeys.ACCELERATOR is SET TO -- the user's/API caller's stored preference,
        // independent of whether an engine is actually running right now or, if one is, what
        // it is actually running on (a per-request accelerator override on chat/completions can
        // reinitialize the engine onto a different accelerator without durably changing this
        // value; see ChatHandler.kt's snapshot/restore). Always defined, loaded or not.
        val preferredAccelerator = this
            .getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = honestDefaultAcceleratorLabel(this))
            .trim()
            .lowercase()
        // What the engine is ACTUALLY running on right now. Only meaningful while a live
        // instance exists -- modelRegistry.getEngineAccelerator is recorded at every successful
        // (re)initialization (OpenAiServer.reinitializeModel and ModelRegistry.initializeModel)
        // and is never cleared on cleanup, so it must not be trusted once instance == null; a
        // model with no live instance has no engine to report on, so this falls back to the
        // stored preference the same way status falls back to "available" below. The defensive
        // `?: preferredAccelerator` on the loaded branch covers a live instance whose
        // initialization this registry never got to observe (there is no such call site today,
        // but reporting the honest preference is a safer fallback than an empty string).
        val actualAccelerator = if (this.instance != null) {
            modelRegistry.getEngineAccelerator(this.name)?.trim()?.lowercase() ?: preferredAccelerator
        } else {
            preferredAccelerator
        }
        // Every accelerator this model SUPPORTS, from Model.accelerators (populated at import
        // time from ConfigKeys.COMPATIBLE_ACCELERATORS -- see ModelRegistry.createModelFromImportedModelInfo).
        val compatibleAccelerators = this.accelerators.map { it.label.lowercase() }

        return ModelData(
            id = this.name,
            created = System.currentTimeMillis() / 1000,
            runtime = runtime,
            accelerator = actualAccelerator,
            preferred_accelerator = preferredAccelerator,
            compatible_accelerators = compatibleAccelerators,
            // See ModelData.accelerators for why this duplicates `accelerator` above -- it
            // follows the engine's actual accelerator too, same as `accelerator` does.
            accelerators = listOf(actualAccelerator),
            status = if (this.instance != null) "loaded" else "available",
        )
    }

    // Unpins and cleans up every model the API server pinned before tearing down the embedded
    // server, so a client-loaded model doesn't outlive the server that loaded it. Fire-and-forget
    // (cleanupModel is not suspend -- it completes its native teardown asynchronously via its own
    // onDone callback).
    //
    // The actual embedded-server teardown (`EmbeddedServer.stop(1000, 2000)`) blocks the calling
    // thread for up to 2s while it drains connections. This function is `suspend` and hops that
    // one blocking call onto Dispatchers.IO so a caller on Dispatchers.Main (e.g.
    // OpenAiServerService's serviceScope) never blocks -- while still `withContext`-awaiting it,
    // so callers that depend on the stop having completed before proceeding (the rebind path)
    // keep that ordering guarantee. The unpin/cleanup loop above stays on the caller's context,
    // unchanged from before.
    suspend fun stop() {
        val pinned = OpenAiServerState.pinnedModels.value.toList()
        for (name in pinned) {
            // Passing context here also clears the persisted "last pinned" boot target for
            // every model this unpins.
            OpenAiServerState.unpin(name, context)
            val task = modelRegistry.tasks.find { t -> t.models.any { it.name == name } }
            val model = task?.models?.find { it.name == name }
            if (task != null && model != null) {
                modelRegistry.cleanupModel(context = context, task = task, model = model)
            }
        }
        val embedded = server
        server = null
        if (embedded != null) {
            withContext(Dispatchers.IO) {
                embedded.stop(1000, 2000)
            }
        }
        if (OpenAiServerState.runningServer === this) {
            OpenAiServerState.runningServer = null
        }
    }
}
