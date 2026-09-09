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

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.relay.runtime.EngineFamily
import com.google.ai.edge.gallery.relay.runtime.engineFor
import com.google.ai.edge.gallery.relay.server.handlers.BusyResult
import com.google.ai.edge.gallery.relay.server.handlers.NO_BUSY_GUARD_TIMEOUT_MS
import com.google.ai.edge.gallery.relay.server.handlers.withBusyGuard
import com.google.ai.edge.gallery.runtime.runtimeHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.withLock

// taskId for API-driven (re)initializations -- only gates task-scoped capabilities, so a
// stable constant is fine; the API surface has no task concept of its own.
private const val API_TASK_ID = "openai_api"

// Holder id this server registers with ModelRegistry.acquireHold/releaseHold for models it holds.
internal const val HOLDER_API = "api"

// Outcome of loadModel(), mapped to HTTP status codes by callers. Kept top-level so handler
// files taking a `loadModel` function reference can see the type.
sealed class LoadResult {
    data class Loaded(val name: String, val accelerator: String) : LoadResult()
    data class NotFound(val message: String) : LoadResult()
    data class Busy(val message: String) : LoadResult()
    data class Conflict(val message: String) : LoadResult()
    data class Error(val message: String) : LoadResult()
    data class TimedOut(val message: String) : LoadResult()
}

// Fallback when a model's ACCELERATOR config key was never set. Public so ChatHandler can
// share this fallback instead of hardcoding GPU.
fun honestDefaultAcceleratorLabel(model: Model): String =
    model.accelerators.firstOrNull()?.label ?: Accelerator.GPU.label

// Persists (name, accelerator) as the boot-preload target for BootReceiver.
private fun OpenAiServer.pinLastModel(name: String, accelerator: String?) {
    dataStoreRepository.saveServerLastPinned(name, accelerator)
}

// Only clears the persisted pin if it currently points at this model.
internal fun OpenAiServer.unpinIfPinned(name: String) {
    val (pinnedName, _) = dataStoreRepository.readServerLastPinned()
    if (pinnedName == name) dataStoreRepository.saveServerLastPinned(null, null)
}

internal sealed class AcceleratorResult {
    object Ok : AcceleratorResult()
    data class Conflict(val heldBy: String) : AcceleratorResult()
}

internal fun OpenAiServer.usesNpuSlot(accelerator: Accelerator) =
    accelerator == Accelerator.NPU || accelerator == Accelerator.TPU

internal fun OpenAiServer.parseAccelerator(raw: String): Accelerator? =
    Accelerator.values().find { it.label.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }

// Reinitializes only when the accelerator actually changes, enforcing the NPU single-slot rule.
// Compares the engine's ACTUAL accelerator, not the possibly-stale stored preference; call under modelMutexes[model.name].withLock.
internal suspend fun OpenAiServer.ensureAccelerator(model: Model, requestedRaw: Accelerator?): AcceleratorResult {
    val currentLabel = (if (model.instance != null) modelRegistry.getEngineAccelerator(model.name) else null)
        ?: model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = honestDefaultAcceleratorLabel(model))
    val currentAccel = parseAccelerator(currentLabel) ?: Accelerator.GPU
    val requested = requestedRaw ?: currentAccel
    // Also reinitializes with no live instance yet (loadModel on a never-initialized model),
    // since `requested != currentAccel` alone would wrongly skip initialization there.
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

// Tears down and recreates the runtime instance under the new accelerator, always mutating
// model.configValues[ACCELERATOR] -- callers wanting that transient snapshot/restore it themselves.
private suspend fun OpenAiServer.reinitializeModel(model: Model, accelerator: Accelerator) {
    // No separate accelerator param on initialize() -- must be set in configValues first, and
    // rolled back to `previousAcceleratorLabel` on failure so a failed reinit leaves no stale config.
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
        // cleanUp() already tore the instance down, so a pinned model left at instance == null
        // would otherwise wedge UI opens behind the 60s cleanup timeout -- roll back and unpin.
        model.configValues =
            model.configValues + (ConfigKeys.ACCELERATOR.label to previousAcceleratorLabel)
        // A boot should not retry a configuration known to have just failed.
        modelRegistry.releaseHold(model.name, HOLDER_API)
        unpinIfPinned(model.name)
        throw IllegalStateException(
            "Failed to reinitialize model '${model.name}' on ${accelerator.label}: $error"
        )
    }
    // Recorded regardless of whether configValues[ACCELERATOR] is about to be restored by the
    // caller -- this must keep tracking what the engine is really doing, unlike that config value.
    modelRegistry.recordEngineAccelerator(model.name, accelerator.label)
}

// Resolves `name`, initializes it via ensureAccelerator/reinitializeModel if needed, and pins it
// so cleanupModel no-ops until unloadModel()/server-stop unpins it. `timeoutMs` bounds the wait.
suspend fun OpenAiServer.loadModel(
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
        // Already loaded -- hold it and report success rather than re-triggering initialization.
        modelRegistry.acquireHold(name, HOLDER_API)
        pinLastModel(name, currentAcceleratorLabel(model))
        return LoadResult.Loaded(name, currentAcceleratorLabel(model))
    }

    val downloaded = modelRegistry.getModelDownloadStatus(model).status ==
        ModelDownloadStatusType.SUCCEEDED
    if (!downloaded) {
        return LoadResult.NotFound("Model '$name' is not downloaded")
    }

    // One held model per engine kind: refuse a second load of the same kind rather than
    // silently evicting the held one.
    val engine = model.engineFor(task.id)
    val conflicting = modelRegistry.heldModelNames()
        .filter { it != name }
        .firstOrNull { heldName ->
            val heldModel = tasks.flatMap { it.models }.find { it.name == heldName }
            val heldTask = tasks.find { t -> t.models.any { it.name == heldName } }
            heldModel != null && heldTask != null && heldModel.engineFor(heldTask.id).family == engine.family
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

    // StableDiffusion/Whisper are CPU-only; a non-CPU request for one fails clearly here (400)
    // rather than reaching reinitializeModel()'s LLM-only path and failing with an opaque 500.
    if (requestedAccel != null && engine.family != EngineFamily.LLM && requestedAccel != Accelerator.CPU) {
        return LoadResult.Error(
            "Model '$name' uses a CPU-only ${engine.wireName} engine and does not " +
                "support accelerator '${requestedAccel.label.lowercase()}'."
        )
    }

    // Same per-model busy-guard (atomic tryLock, no TOCTOU) every other route uses -- a racing
    // request gets BusyResult.Busy immediately instead of queueing.
    val guardResult = withBusyGuard(
        mutexes = modelMutexes,
        key = model.name,
        timeoutMs = timeoutMs,
    ) {
        if (model.instance != null) {
            LoadResult.Loaded(name, currentAcceleratorLabel(model)) as LoadResult
        } else if (requestedAccel == null && engine.family != EngineFamily.LLM) {
            // No explicit accelerator + CPU-only kind: initialise the way the app's UI does
            // (initializeModelFn) instead of forcing the recorded (often stale) default accelerator.
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
                modelRegistry.acquireHold(name, HOLDER_API)
                pinLastModel(name, currentAcceleratorLabel(model))
                LoadResult.Loaded(name, currentAcceleratorLabel(model)) as LoadResult
            }
        } else {
            when (val result = ensureAccelerator(model, requestedAccel)) {
                is AcceleratorResult.Conflict -> LoadResult.Busy(
                    "NPU is currently held by model '${result.heldBy}'; only one model " +
                        "may use the NPU at a time on this device."
                ) as LoadResult
                is AcceleratorResult.Ok -> {
                    modelRegistry.acquireHold(name, HOLDER_API)
                    pinLastModel(name, currentAcceleratorLabel(model))
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

private fun OpenAiServer.currentAcceleratorLabel(model: Model): String =
    model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = honestDefaultAcceleratorLabel(model))

// Releases the hold first so cleanupModel's holder guard doesn't no-op this, then awaits
// teardown so the caller doesn't return until the native free actually completes.
suspend fun OpenAiServer.unloadModel(name: String) {
    modelRegistry.releaseHold(name, HOLDER_API)
    unpinIfPinned(name)
    val task = modelRegistry.tasks.find { t -> t.models.any { it.name == name } }
        ?: return
    val model = task.models.find { it.name == name } ?: return
    modelRegistry.cleanupModelAwait(context = context, task = task, model = model)
}
