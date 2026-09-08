// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.litertlm.Contents
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// Generous: a stable-diffusion native free can block for a long time.
private const val CLEANUP_AWAIT_TIMEOUT_MS = 60_000L

class ModelLifecycle(
  private val registryScope: CoroutineScope,
  private val taskCatalog: TaskCatalog,
  private val systemPromptRepository: SystemPromptRepository,
) {

  // Guarded by cleaningUpLock, which is never held across a suspension point.
  private val cleaningUpDeferreds = mutableMapOf<String, CompletableDeferred<Unit>>()
  private val cleaningUpLock = Any()

  private val initializedBackends = mutableMapOf<String, MutableSet<String>>()

  // ConcurrentHashMap: its two writer sites run on different coroutine owners and can race the
  // same key. Callers must also check model.instance != null before trusting a lookup.
  private val engineAccelerators = ConcurrentHashMap<String, String>()

  fun recordEngineAccelerator(modelName: String, acceleratorLabel: String) {
    engineAccelerators[modelName] = acceleratorLabel
  }

  fun getEngineAccelerator(modelName: String): String? = engineAccelerators[modelName]

  private val holdsLock = Any()
  private val _holds = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

  fun acquireHold(modelName: String, holder: String) {
    synchronized(holdsLock) {
      val current = _holds.value
      val holders = current[modelName] ?: emptySet()
      _holds.value = current + (modelName to (holders + holder))
    }
  }

  fun releaseHold(modelName: String, holder: String) {
    synchronized(holdsLock) {
      val current = _holds.value
      val holders = current[modelName] ?: return
      val remaining = holders - holder
      _holds.value =
        if (remaining.isEmpty()) {
          current - modelName
        } else {
          current + (modelName to remaining)
        }
    }
  }

  fun holdersOf(modelName: String): Set<String> = _holds.value[modelName] ?: emptySet()

  fun heldModelNames(): Set<String> = _holds.value.keys

  fun isFirstInitialization(model: Model): Boolean {
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    return !initializedBackends.getOrDefault(model.name, emptySet()).contains(backend)
  }

  fun forgetInitializedBackends(modelName: String) {
    initializedBackends.remove(modelName)
  }

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) {
    registryScope.launch {
      awaitCleanupIfInFlight(model)

      if (!force && model.initStatusFlow.value is Model.InitializationStatus.Initialized) {
        Log.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
        onDone()
        return@launch
      }

      if (model.initializing) {
        model.cleanUpAfterInit = false
        Log.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
        return@launch
      }

      withContext(Dispatchers.Default) {
        cleanupModelAwait(context = context, task = task, model = model)
      }

      Log.d(TAG, "Initializing model '${model.name}'...")
      model.markInitializationStarted()

      val onDoneFn: (error: String) -> Unit = { error ->
        if (model.instance != null) {
          Log.d(TAG, "Model '${model.name}' initialized successfully")
          val backend =
            model.getStringConfigValue(
              key = ConfigKeys.ACCELERATOR,
              defaultValue = Accelerator.GPU.label,
            )
          initializedBackends.getOrPut(model.name) { mutableSetOf() }.add(backend)
          recordEngineAccelerator(model.name, backend)
          if (model.cleanUpAfterInit) {
            model.markInitializationFailed(
              IllegalStateException("Model cleaned up after initialization")
            )
            Log.d(TAG, "Model '${model.name}' needs cleaning up after init.")
            cleanupModel(context = context, task = task, model = model)
          } else {
            model.markInitialized()
          }
          onDone()
        } else if (error.isNotEmpty()) {
          model.markInitializationFailed(error)
          Log.d(TAG, "Model '${model.name}' failed to initialize")
          onError(error)
        } else {
          model.markInitialized(null)
        }
      }

      val systemPrompt = SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      withContext(Dispatchers.IO) {
        taskCatalog.getCustomTaskByTaskId(id = task.id)
          ?.initializeModelFn(
            context = context,
            coroutineScope = registryScope,
            model = model,
            systemInstruction = Contents.of(systemPrompt),
            onDone = onDoneFn,
          )
      }
    }
  }

  suspend fun cleanupModelAwait(context: Context, task: Task, model: Model) {
    val deferred = CompletableDeferred<Unit>()
    cleanupModel(
      context = context,
      task = task,
      model = model,
      onDone = { deferred.complete(Unit) },
    )
    val completed = withTimeoutOrNull(CLEANUP_AWAIT_TIMEOUT_MS) { deferred.await() }
    if (completed == null) {
      Log.e(
        TAG,
        "Timed out after ${CLEANUP_AWAIT_TIMEOUT_MS}ms waiting for cleanup of model " +
          "'${model.name}' to finish. Proceeding anyway; cleanup will complete in the " +
          "background whenever the native free returns.",
      )
    }
  }

  private suspend fun awaitCleanupIfInFlight(model: Model) {
    val deferred = synchronized(cleaningUpLock) { cleaningUpDeferreds[model.name] } ?: return
    Log.d(TAG, "Cleanup already in flight for '${model.name}'; waiting before initializing.")
    val completed = withTimeoutOrNull(CLEANUP_AWAIT_TIMEOUT_MS) { deferred.await() }
    if (completed == null) {
      Log.e(
        TAG,
        "Timed out after ${CLEANUP_AWAIT_TIMEOUT_MS}ms waiting for in-flight cleanup of model " +
          "'${model.name}' before initializing. Proceeding anyway.",
      )
    }
  }

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) {
    // Guard on the holds state; must remain the first statement in this function.
    if (holdersOf(model.name).isNotEmpty()) {
      onDone()
      return
    }
    if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
      Log.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
      onDone()
      return
    }

    val existingDeferred = synchronized(cleaningUpLock) { cleaningUpDeferreds[model.name] }
    if (existingDeferred != null) {
      Log.d(TAG, "Cleanup already in flight for '${model.name}'; chaining onto it.")
      existingDeferred.invokeOnCompletion { onDone() }
      return
    }

    if (model.instance != null) {
      model.cleanUpAfterInit = false
      Log.d(TAG, "Cleaning up model '${model.name}'...")
      val deferred = CompletableDeferred<Unit>()
      synchronized(cleaningUpLock) { cleaningUpDeferreds[model.name] = deferred }
      val onDoneFn: () -> Unit = {
        model.resetInitialization()
        synchronized(cleaningUpLock) { cleaningUpDeferreds.remove(model.name) }
        Log.d(TAG, "Clean up model '${model.name}' done")
        deferred.complete(Unit)
        onDone()
      }
      val customTask = taskCatalog.getCustomTaskByTaskId(id = task.id)
      Log.d(TAG, "Invoking stopGenerationFn for model '${model.name}' before cleanup")
      customTask?.stopGenerationFn(model)
      customTask
        ?.cleanUpModelFn(
          context = context,
          coroutineScope = registryScope,
          model = model,
          onDone = onDoneFn,
        )
    } else {
      if (model.initializing) {
        Log.d(
          TAG,
          "Model '${model.name}' is still initializing.. Will clean up after it is done initializing",
        )
        model.cleanUpAfterInit = true
      }
      onDone()
    }
  }
}
