/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.relay.modelmanager

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.common.isAICoreSupported
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SD_IMPORTS_DIR
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.relay.engine.InferenceEngineType
import com.google.ai.edge.gallery.security.OfflineMode
import com.google.ai.edge.litertlm.Contents
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGModelRegistry"

// DELIBERATE DUPLICATION -- read before "de-duplicating" anything below.
//
// The allowlist file names/URL, the local-test-json override, the disk/assets read functions, the
// fetch-then-parse orchestration, and the nine file-system/task helpers near the bottom of this
// file (createModelFromImportedModelInfo, fetchModelAllowlist, applyModelAllowlist,
// groupTasksByCategory, getModelDownloadStatus, isFileInModelsDir, deleteFileFromModelsDir,
// deleteFilesFromImportDir, deleteDirFromModelsDir, isModelDownloaded) each exist a second time in
// ui/modelmanager/ModelManagerViewModel.kt, as private members of that class.
//
// That is on purpose. ModelManagerViewModel.kt is a file upstream (google-ai-edge/gallery) still
// edits heavily; relay re-merges it. Relocating those helpers out of Google's class -- or
// widening them to internal so this file could import them -- would rewrite a large region of
// Google's file and turn every future upstream edit to it into a merge conflict. Keeping private
// copies here instead means Google's file receives only the small delegation hook it already has
// (a `modelRegistry` constructor parameter plus one-line forwarders), and an upstream merge of
// ModelManagerViewModel.kt applies cleanly.
//
// The cost is that these copies can silently drift from Google's. AT EVERY UPSTREAM SYNC, diff the
// helpers below against their counterparts in ModelManagerViewModel.kt and port any behavioural
// change across. The bodies here were taken from merged-base's ModelManagerViewModel.kt, adapted
// only to take `context`/`modelsDir` explicitly instead of reading view-model fields.

private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val MODEL_ALLOWLIST_TEST_FILENAME = "model_allowlist_test.json"
private const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

private const val TEST_MODEL_ALLOW_LIST = ""

// Copy of ModelManagerViewModel.kt's private file-scope list, per the duplication note above.
// Deliberately merged-base's ordering, NOT the relay's reordered variant -- the reorder is a
// separate change and is not being carried here.
private val PREDEFINED_LLM_TASK_ORDER =
  listOf(
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
    BuiltInTaskId.MP_SCRAPBOOK,
  )

// Read by restoreImportedModels() below (its only reader in this file; ModelManagerViewModel.kt
// has its own copy for addImportedLlmModel, which needs its own instance since that codepath
// stays Activity-side).
private val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )

// Generous: a stable-diffusion native free can block for up to one graph node, which on this
// device can take tens of seconds.
private const val CLEANUP_AWAIT_TIMEOUT_MS = 60_000L

/**
 * Process-scoped owner of model/task lifecycle state. [ModelManagerViewModel] is a thin
 * Activity-side view over this registry for task/model reads, the file-system helpers, the
 * initializeModel/cleanupModel lifecycle engine, and loadModelAllowlist()/allowlistModels. This
 * is what makes `getAllModels()` non-empty on a genuinely headless start (boot, no Activity ever
 * run): before this class existed, nothing populated `task.models` except an Activity-driven
 * call.
 *
 * Modeled on the existing `NotificationScheduleManager` precedent (`@Singleton` with a
 * self-owned, never-explicitly-cancelled `CoroutineScope`) rather than inventing a new pattern.
 */
@Singleton
class ModelRegistry
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val customTasks: Set<@JvmSuppressWildcards CustomTask>,
  private val systemPromptRepository: SystemPromptRepository,
  private val dataStoreRepository: DataStoreRepository,
) {

  private val modelsDir = getModelStorageDir(context)

  // Process-scoped scope for model-lifecycle work (init/cleanup) that must outlive any single
  // Activity/ViewModel -- the entire point of this class. SupervisorJob (not a plain Job/the
  // scope's default) so that one model's init failure does not cancel a concurrent in-flight
  // operation for a different model sharing this scope. Never explicitly cancelled, same as
  // NotificationScheduleManager.coroutineScope -- process death cancels it implicitly, which is
  // the correct lifetime for a background server.
  private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // The process-scoped allowlist-model list. Only [loadModelAllowlist] mutates this, and only
  // from inside the withContext(Dispatchers.Main) block below -- see the race analysis on
  // [loadModelAllowlist] for why that holds even though the owning coroutine runs on
  // [registryScope], not viewModelScope.
  private var _allowlistModels: MutableList<Model> = mutableListOf()
  val allowlistModels: List<Model>
    get() = _allowlistModels

  // Single-flight guard for loadModelAllowlist(): synchronized (Any) monitor, not Mutex, because
  // the check-and-either-create-or-join decision below never suspends -- it's a plain compare-
  // and-set, matching the style already used for cleaningUpLock/cleaningUpDeferreds above.
  private val allowlistLoadLock = Any()
  private var allowlistLoadDeferred: CompletableDeferred<String?>? = null

  // Explicit in-flight-cleanup marker, keyed by model name. Presence of a key means a
  // cleanUpModelFn teardown for that model has been started but its onDone has not fired yet.
  // Guarded by cleaningUpLock; never held across a suspension point.
  private val cleaningUpDeferreds = mutableMapOf<String, CompletableDeferred<Unit>>()
  private val cleaningUpLock = Any()

  private val initializedBackends = mutableMapOf<String, MutableSet<String>>()

  // Box: the accelerator each model's engine is ACTUALLY initialized/running on right now,
  // keyed by model name -- distinct from a model's stored ConfigKeys.ACCELERATOR value, which
  // is the user's/API caller's PREFERENCE and can legitimately lag the engine (a per-request
  // accelerator override on chat/completions reinitializes the engine but restores the stored
  // value afterwards; see openai/handlers/ChatHandler.kt). Written from both convergent
  // initialization paths: [initializeModel]'s onDoneFn below (the UI-driven path, also used by
  // the API server for its CPU-only Whisper/StableDiffusion loads) and OpenAiServer's own
  // reinitializeModel (which bypasses initializeModel for LLM models so a per-request override
  // never durably touches this registry's other bookkeeping). ConcurrentHashMap, unlike
  // initializedBackends above, because those two writer call sites run on different coroutine
  // owners and can genuinely race on the same key. A caller must additionally check
  // model.instance != null before trusting a lookup here -- an entry is never cleared on
  // cleanup, so it goes stale (but harmlessly unread) once the model has no live instance.
  private val engineAccelerators = ConcurrentHashMap<String, String>()

  /** Records that [modelName]'s engine is now actually running on [acceleratorLabel]. */
  fun recordEngineAccelerator(modelName: String, acceleratorLabel: String) {
    engineAccelerators[modelName] = acceleratorLabel
  }

  /**
   * The accelerator [modelName]'s engine is actually running on, or null if this registry has
   * never recorded one for it (e.g. the process has not initialized this model yet). Callers
   * must also check model.instance != null -- see [engineAccelerators].
   */
  fun getEngineAccelerator(modelName: String): String? = engineAccelerators[modelName]

  fun isFirstInitialization(model: Model): Boolean {
    val backend =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    return !initializedBackends.getOrDefault(model.name, emptySet()).contains(backend)
  }

  /**
   * Forgets that [modelName] has ever been initialized against any backend. Called from
   * [ModelManagerViewModel.deleteModel] (which stays Activity-side in this migration step) so a
   * deleted model's "first initialization" bookkeeping doesn't survive the delete.
   */
  fun forgetInitializedBackends(modelName: String) {
    initializedBackends.remove(modelName)
  }

  fun getTaskById(id: String): Task? {
    return getActiveCustomTasks().map { it.task }.find { it.id == id }
  }

  fun getTasksByIds(ids: Set<String>): List<Task> {
    return getActiveCustomTasks().map { it.task }.filter { ids.contains(it.id) }
  }

  fun getCustomTaskByTaskId(id: String): CustomTask? {
    return getActiveCustomTasks().find { it.task.id == id }
  }

  fun getActiveCustomTasks(): List<CustomTask> {
    return customTasks.toList()
  }

  // Read-only equivalent of ModelManagerViewModel.uiState.value.tasks for process-scoped
  // callers (OpenAiServer, its route handlers) that must not depend on an Activity-owned
  // ViewModel. Same Task object references as uiState.tasks. Task shells exist immediately
  // (customTasks is populated at Hilt injection), but each task's `models` list stays empty
  // until [loadModelAllowlist] (below) runs at least once and completes -- that call is no
  // longer Activity-gated (MainActivity.kt can still trigger it, but so can a boot path with no
  // Activity ever having run). A process that has not yet had loadModelAllowlist() complete
  // therefore sees non-empty `tasks` with empty `models`, not an empty `tasks` list -- callers
  // must check for models (e.g. getAllModels().isEmpty()), not for this list being non-empty.
  val tasks: List<Task>
    get() = getActiveCustomTasks().map { it.task }

  fun getModelByName(name: String): Model? {
    for (task in getActiveCustomTasks().map { it.task }) {
      for (model in task.models) {
        if (model.name == name) {
          return model
        }
      }
    }
    return null
  }

  fun getAllModels(): List<Model> {
    val allModels = mutableSetOf<Model>()
    for (task in getActiveCustomTasks().map { it.task }) {
      for (model in task.models) {
        allModels.add(model)
      }
    }
    return allModels.toList().sortedBy { it.displayName.ifEmpty { it.name } }
  }

  fun processTasks() {
    val curTasks = getActiveCustomTasks().map { it.task }
    for (task in curTasks) {
      for (model in task.models) {
        model.preProcess()
      }
      // Move the model that is best for this task to the front.
      val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) }
      if (bestModel != null) {
        task.models.remove(bestModel)
        task.models.add(0, bestModel)
      }
    }
  }

  /**
   * Adds [model] to [task]'s model list only if a model with the same name isn't already
   * present.
   *
   * Guards against the duplicate-key LazyColumn crash caused by [restoreImportedModels] (and
   * therefore anything that calls it) running more than once per process -- boot start followed
   * by the user opening the app, or an Activity recreation -- which would otherwise re-append the
   * same imported model on every run.
   */
  private fun addModelIfAbsent(task: Task?, model: Model) {
    if (task != null && task.models.none { it.name == model.name }) {
      task.models.add(model)
    }
  }

  /**
   * Turns every persisted imported-model record (LLM `.litertlm`/`.task` imports tracked in
   * [dataStoreRepository], and SD `.gguf` imports found by scanning [SD_IMPORTS_DIR]) into a live
   * [Model] and attaches it to its task(s). Needs no Activity: [dataStoreRepository], [modelsDir]
   * and [getActiveCustomTasks] are all already process-scoped on this class, so this can run from
   * a boot receiver with no UI ever started, not just from an Activity-driven reload.
   *
   * Safe to call more than once per process. Every attach goes through [addModelIfAbsent]'s
   * name-based duplicate guard, so a call made at boot followed by a later call made when the
   * user opens the app (or an Activity recreation) does not append a second copy of the same
   * model.
   */
  fun restoreImportedModels() {
    val curTasks = getActiveCustomTasks().map { it.task }

    for (importedModel in dataStoreRepository.readImportedModels()) {
      Log.d(TAG, "stored imported model: $importedModel")
      val model = createModelFromImportedModelInfo(info = importedModel)

      addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_CHAT }, model)
      addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_PROMPT_LAB }, model)
      addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_AGENT_CHAT }, model)
      if (model.llmSupportImage) {
        addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_ASK_IMAGE }, model)
      }
      if (model.llmSupportAudio) {
        addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_ASK_AUDIO }, model)
      }
      if (model.llmSupportTinyGarden) {
        addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_TINY_GARDEN }, model)
        val newConfigs = model.configs.toMutableList()
        newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
        model.configs = newConfigs
        model.preProcess()
      }
      if (model.llmSupportMobileActions) {
        addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_MOBILE_ACTIONS }, model)
      }
    }

    val sdImportsDir = File(modelsDir, SD_IMPORTS_DIR)
    if (sdImportsDir.exists()) {
      val imageGenTask = curTasks.find { it.id == BuiltInTaskId.IMAGE_GEN }
      for (file in sdImportsDir.listFiles { _, name -> name.endsWith(".gguf") } ?: emptyArray()) {
        val model = createImportedSdModel(fileName = file.name, fileSize = file.length())
        addModelIfAbsent(imageGenTask, model)
      }
    }
  }

  /**
   * Builds a live [Model] for an imported stable-diffusion `.gguf` file. Also used by the
   * interactive import flow, same reasoning as [createModelFromImportedModelInfo] below: a plain
   * data-conversion function with no task-attachment side effect of its own. SD import support was
   * added in relay, so it stays defined here rather than being ported anywhere.
   */
  fun createImportedSdModel(fileName: String, fileSize: Long): Model =
    Model(
        name = fileName,
        info = "Imported SD GGUF model",
        url = "",
        sizeInBytes = fileSize,
        downloadFileName = "$SD_IMPORTS_DIR${File.separator}$fileName",
        configs =
          mutableListOf(
            NumberSliderConfig(
              key = ConfigKey("sd_steps", "Steps", R.string.config_label_sd_steps),
              sliderMin = 1f,
              sliderMax = 50f,
              defaultValue = 20f,
              valueType = ValueType.INT,
              needReinitialization = false,
            ),
            NumberSliderConfig(
              key = ConfigKey("sd_cfg", "CFG Scale", R.string.config_label_sd_cfg),
              sliderMin = 1f,
              sliderMax = 20f,
              defaultValue = 7.5f,
              valueType = ValueType.FLOAT,
              needReinitialization = false,
            ),
          ),
        showRunAgainButton = false,
        imported = true,
      )
      .also { it.preProcess() }

  /**
   * Loads the model allowlist (from the test file on disk, a local test constant, the network, or
   * the last-saved-to-disk copy, in that order) and populates [_allowlistModels] and each active
   * task's `task.models`.
   *
   * Runs on [registryScope] instead of `viewModelScope`, so a boot-triggered load isn't
   * cancelled when an Activity's ViewModel is cleared. The mutation of `_allowlistModels` and
   * `task.models` still happens inside `withContext(Dispatchers.Main)`, so Compose recomposition
   * and this mutation still cannot interleave, because both run on the single Main thread.
   *
   * Idempotent and safe under concurrent callers (e.g. a boot-triggered load racing a user
   * opening the app). The first caller to arrive becomes the owner and does the actual work;
   * every other concurrent caller joins that same in-flight [CompletableDeferred] and receives
   * the same [onDone]/[onError] outcome, rather than starting a second redundant load (which
   * would double the network/disk I/O and could re-clear/rebuild `_allowlistModels`/
   * `task.models` twice in quick succession). Joining was chosen over silently no-oping so a
   * caller that genuinely needs to know completion always gets a definite signal, matching the
   * onDone/onError contract [initializeModel]/[cleanupModel] already use elsewhere in this
   * class.
   *
   * Functionally unchanged from the original: same allowlist source order, same per-model
   * filtering (disabled flag, AICore availability, NPU/SOC gating), same task/modelNames wiring,
   * same duplicate-guard (`task.models.none { it.name == model.name }`).
   */
  fun loadModelAllowlist(onDone: () -> Unit = {}, onError: (String) -> Unit = {}) {
    val (deferred, isOwner) =
      synchronized(allowlistLoadLock) {
        val existing = allowlistLoadDeferred
        if (existing != null) {
          existing to false
        } else {
          val fresh = CompletableDeferred<String?>()
          allowlistLoadDeferred = fresh
          fresh to true
        }
      }

    registryScope.launch {
      if (isOwner) {
        val error =
          try {
            withContext(Dispatchers.IO) { runLoadModelAllowlist() }
          } catch (e: Exception) {
            Log.e(TAG, "Failed to load model allowlist", e)
            "Failed to load model allowlist"
          } finally {
            // Cleared under the same lock, so the NEXT call (not one already joined above) starts
            // a fresh load rather than joining this now-completed one forever.
            synchronized(allowlistLoadLock) {
              if (allowlistLoadDeferred === deferred) {
                allowlistLoadDeferred = null
              }
            }
          }
        deferred.complete(error)
      }

      val error = deferred.await()
      withContext(Dispatchers.Main) {
        if (error != null) {
          onError(error)
        } else {
          onDone()
        }
      }
    }
  }

  /**
   * The actual fetch/parse/mutate work for [loadModelAllowlist], run on [Dispatchers.IO] except
   * for the final mutation block. Returns an error message, or null on success.
   */
  private suspend fun runLoadModelAllowlist(): String? {
    // fetchModelAllowlist() (private, below) does the actual disk/assets fetch-and-parse -- pure
    // I/O with no registry-state mutation of its own. Source order matches merged-base's
    // ModelManagerViewModel.loadModelAllowlist(): test file on disk, local test constant, bundled
    // assets, then the last-saved-to-disk copy.
    val modelAllowlist =
      fetchModelAllowlist(context = context, modelsDir = modelsDir)
        ?: return "Failed to load model list"

    Log.d(TAG, "Allowlist: $modelAllowlist")

    val curTasks = getActiveCustomTasks().map { it.task }

    // Everything below mutates the shared MutableLists that Main-thread Compose readers iterate
    // concurrently -- allowlistModels (GlobalModelManager.kt's `allowlistModels.withIndex()`)
    // and task.models (ModelList.kt's `task.models.toList()`, ResponsePanel.kt's
    // `task.models[pagerState.settledPage]` with `pageCount = { task.models.size }`,
    // HomeScreen.kt's `task.models.size`). Mutating these from a background coroutine while
    // composition iterates the same ArrayList instances on Main is a
    // ConcurrentModificationException/IndexOutOfBoundsException waiting to happen. Performing
    // all the mutations under withContext(Dispatchers.Main) makes them run atomically with
    // respect to composition (Compose recomposition and this block can never interleave, since
    // both run on the same thread), rather than racing it from a background dispatcher.
    //
    // applyModelAllowlist() (private, below) is synchronous and starts no coroutine of its own,
    // so running it here keeps every mutation it does -- the allowlist conversion loop and the
    // task.modelNames pass -- inside this same Main-thread block, same as before this call was
    // extracted out of this function.
    withContext(Dispatchers.Main) {
      // Clear existing allowlist models. Done here, immediately before rebuilding, so the clear
      // and rebuild are atomic from a Main-thread-reader's point of view instead of leaving a
      // window (as before) where a Main-thread reader could observe an already-cleared-but-not-
      // yet-rebuilt list.
      _allowlistModels.clear()

      applyModelAllowlist(
        modelAllowlist = modelAllowlist,
        curTasks = curTasks,
        allowlistModels = _allowlistModels,
      )

      // Process all tasks.
      Log.d(TAG, "loadModelAllowlist: Processing tasks")
      processTasks()
    }

    return null
  }

  // The sort logic itself is the private groupTasksByCategory(context, tasks) below (with
  // getCategoryLabel()) -- a duplicate of ModelManagerViewModel.kt's private copy, per the
  // duplication note at the top of this file.
  fun groupTasksByCategory(): Map<String, List<Task>> =
    groupTasksByCategory(context = context, tasks = getActiveCustomTasks().map { it.task })

  /**
   * Retrieves the download status of a model.
   *
   * This function determines the download status of a given model by checking if it's fully
   * downloaded, partially downloaded, or not downloaded at all. It also retrieves the received and
   * total bytes for partially downloaded models.
   *
   * The body (and the isModelDownloaded/checkIfModelDownloaded/isModelPartiallyDownloaded chain it
   * calls) is duplicated privately at the bottom of this file, per the duplication note at the
   * top.
   */
  fun getModelDownloadStatus(model: Model): ModelDownloadStatus =
    getModelDownloadStatus(context = context, modelsDir = modelsDir, model = model)

  fun isFileInModelsDir(fileName: String): Boolean = isFileInModelsDir(modelsDir, fileName)

  // Kept here rather than moved: its body doesn't touch modelsDir/context at all, so a "pure"
  // top-level version would have the exact same (fileName: String) signature as this member --
  // an unqualified call to it from within this class would resolve back to this member itself
  // (member functions shadow a same-signature top-level function in their own class body),
  // making the delegation silently recurse instead of calling the moved copy. Not worth the
  // three-line function's savings.
  fun isFileInDataLocalTmpDir(fileName: String): Boolean {
    val file = File("/data/local/tmp", fileName)
    return file.exists()
  }

  fun deleteFileFromModelsDir(fileName: String) = deleteFileFromModelsDir(modelsDir, fileName)

  /**
   * Deletes files from the model imports directory whose absolute paths start with a given
   * prefix.
   */
  fun deleteFilesFromImportDir(fileName: String) = deleteFilesFromImportDir(modelsDir, fileName)

  fun deleteDirFromModelsDir(dir: String) = deleteDirFromModelsDir(modelsDir, dir)

  fun isModelDownloaded(model: Model): Boolean = isModelDownloaded(modelsDir, model)

  /**
   * The model-lifecycle engine: initializes a model on [registryScope] rather than any
   * Activity-owned scope, so a service-initiated load no longer depends on an Activity's
   * ViewModel scope being alive.
   */
  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) {
    registryScope.launch {
      // If a cleanup for this model is still in flight (its cleanUpModelFn onDone hasn't fired
      // yet), the model's initStatusFlow can still read Initialized / instance can still be
      // non-null even though it is on its way out. Waiting here first means the "already
      // initialized" check below sees the post-cleanup state, not a stale one.
      awaitCleanupIfInFlight(model)

      // Skip if initialized already.
      if (!force && model.initStatusFlow.value is Model.InitializationStatus.Initialized) {
        Log.d(TAG, "Model '${model.name}' has been initialized. Skipping.")
        onDone()
        return@launch
      }

      // Skip if initialization is in progress.
      if (model.initializing) {
        model.cleanUpAfterInit = false
        Log.d(TAG, "Model '${model.name}' is being initialized. Skipping.")
        return@launch
      }

      // Clean up and WAIT for it to actually finish (cleanUpModelFn's onDone, not just the
      // launch call returning) before starting a new init -- otherwise the old cleanup's native
      // free can still be in flight while a new instance is created. Run off the Main thread:
      // cleanupModel() -> cleanUpModelFn() ends up doing synchronous JNI closes.
      withContext(Dispatchers.Default) {
        cleanupModelAwait(context = context, task = task, model = model)
      }

      // Start initialization.
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
          // Box: this is the UI-driven half of engineAccelerators's bookkeeping -- see its
          // doc comment for why OpenAiServer's own reinitializeModel records the other half.
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

      // Call the model initialization function.
      val systemPrompt = SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      withContext(Dispatchers.IO) {
        getCustomTaskByTaskId(id = task.id)
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

  /**
   * Suspending wrapper around [cleanupModel] that completes only once the task's
   * `cleanUpModelFn` has actually invoked its `onDone` callback (i.e. the native free has
   * finished), not merely once `cleanUpModelFn` has returned from launching that work.
   */
  private suspend fun cleanupModelAwait(context: Context, task: Task, model: Model) {
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

  /**
   * If [model] has a cleanup in flight (see [cleaningUpDeferreds]), suspends until it finishes
   * (or times out) so callers -- namely [initializeModel] -- observe the post-cleanup state
   * rather than a stale Initialized/non-null-instance snapshot.
   */
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
    // A model the API server loaded (POST /v1/models/{id}/load) is pinned so it survives the
    // UI navigating away from its screen -- see OpenAiServerState.pin/unpin. This check must
    // stay the very first statement in this function: every other branch below assumes a
    // pinned model has already exited.
    if (com.google.ai.edge.gallery.relay.openai.OpenAiServerState.isPinned(model.name)) {
      onDone()
      return
    }
    if (instanceToCleanUp != null && instanceToCleanUp !== model.instance) {
      Log.d(TAG, "Stale cleanup request for ${model.name}. Aborting.")
      onDone()
      return
    }

    // A cleanup for this model is already in flight (its onDone hasn't fired yet) -- chain onto
    // the in-flight teardown instead of starting a second one.
    val existingDeferred = synchronized(cleaningUpLock) { cleaningUpDeferreds[model.name] }
    if (existingDeferred != null) {
      Log.d(TAG, "Cleanup already in flight for '${model.name}'; chaining onto it.")
      existingDeferred.invokeOnCompletion { onDone() }
      return
    }

    if (model.instance != null) {
      model.cleanUpAfterInit = false
      Log.d(TAG, "Cleaning up model '${model.name}'...")
      // Marker set SYNCHRONOUSLY, before stopGenerationFn is invoked, so any concurrent
      // cleanupModel()/initializeModel() call for this model that runs after this line (even on
      // another thread) sees the in-flight state immediately.
      val deferred = CompletableDeferred<Unit>()
      synchronized(cleaningUpLock) { cleaningUpDeferreds[model.name] = deferred }
      val onDoneFn: () -> Unit = {
        model.resetInitialization()
        synchronized(cleaningUpLock) { cleaningUpDeferreds.remove(model.name) }
        Log.d(TAG, "Clean up model '${model.name}' done")
        deferred.complete(Unit)
        onDone()
      }
      // Cancel in-flight native work first; the task's cleanUpModelFn then joins and frees.
      val customTask = getCustomTaskByTaskId(id = task.id)
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
      // When model is being initialized and we are trying to clean it up at same time, we mark it
      // to clean up and it will be cleaned up after initialization is done.
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

  // ---------------------------------------------------------------------------------------------
  // Private duplicates of ModelManagerViewModel.kt's private helpers. See the DELIBERATE
  // DUPLICATION note at the top of this file: these are copies, not the canonical definitions, and
  // must be re-diffed against Google's file at every upstream sync. Bodies are merged-base's;
  // signatures take `context`/`modelsDir` explicitly instead of reading view-model fields.
  // ---------------------------------------------------------------------------------------------

  /**
   * Builds a live [Model] from a persisted [ImportedModel] proto record. Plain data conversion with
   * no task-attachment side effect of its own -- callers decide which tasks to attach the result
   * to.
   */
  private fun createModelFromImportedModelInfo(info: ImportedModel): Model {
    val accelerators: MutableList<Accelerator> =
      info.llmConfig.compatibleAcceleratorsList
        .mapNotNull { acceleratorLabel ->
          when (acceleratorLabel.trim()) {
            Accelerator.GPU.label -> Accelerator.GPU
            Accelerator.CPU.label -> Accelerator.CPU
            Accelerator.NPU.label -> Accelerator.NPU

            else -> null // Ignore unknown accelerator labels
          }
        }
        .toMutableList()
    val llmMaxToken = info.llmConfig.defaultMaxTokens
    val llmSupportImage = info.llmConfig.supportImage
    val llmSupportAudio = info.llmConfig.supportAudio
    val llmSupportTinyGarden = info.llmConfig.supportTinyGarden
    val llmSupportMobileActions = info.llmConfig.supportMobileActions
    val llmSupportThinking = info.llmConfig.supportThinking
    val llmSupportSpeculativeDecoding = info.llmConfig.supportSpeculativeDecoding
    val configs: MutableList<Config> =
      createLlmChatConfigs(
          defaultMaxToken = llmMaxToken,
          defaultTopK = info.llmConfig.defaultTopk,
          defaultTopP = info.llmConfig.defaultTopp,
          defaultTemperature = info.llmConfig.defaultTemperature,
          accelerators = accelerators,
          supportThinking = llmSupportThinking,
          supportSpeculativeDecoding = llmSupportSpeculativeDecoding,
        )
        .toMutableList()
    val capabilities: MutableList<ModelCapability> = mutableListOf()
    val capabilityToTaskTypes: MutableMap<ModelCapability, List<String>> = mutableMapOf()
    if (llmSupportThinking) {
      capabilities.add(ModelCapability.LLM_THINKING)
      capabilityToTaskTypes[ModelCapability.LLM_THINKING] =
        listOf(BuiltInTaskId.LLM_CHAT, BuiltInTaskId.LLM_ASK_IMAGE, BuiltInTaskId.LLM_ASK_AUDIO)
    }
    if (llmSupportSpeculativeDecoding) {
      capabilities.add(ModelCapability.SPECULATIVE_DECODING)
      capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING] =
        listOf(
          BuiltInTaskId.LLM_CHAT,
          BuiltInTaskId.LLM_ASK_IMAGE,
          BuiltInTaskId.LLM_ASK_AUDIO,
          BuiltInTaskId.LLM_PROMPT_LAB,
        )
    }
    // Box: For imported models, downloadFileName carries the IMPORTS_DIR prefix (see
    // ModelHelperExt.kt's Model.runtimeHelper), so strip it the same way before checking the
    // extension.
    val importedFileNameToCheck =
      if (info.fileName.startsWith("$IMPORTS_DIR/")) {
        info.fileName.substringAfter("$IMPORTS_DIR/")
      } else {
        info.fileName
      }
    // Box: A GGUF import is not a LiteRT model -- it runs through llama.cpp (see
    // ModelHelperExt.kt's Model.runtimeHelper, which routes by file extension regardless of
    // runtimeType). Tagging it LITERT_LM would make Model.supportModelBenchmark (data/Model.kt)
    // true for it, which puts it on Google's benchmark screen; that screen calls the LiteRT-LM
    // native benchmark API with the .gguf path and crashes. Tag it UNKNOWN instead so
    // supportModelBenchmark stays false for GGUF imports.
    val importedRuntimeType =
      if (InferenceEngineType.fromModelPath(importedFileNameToCheck) == InferenceEngineType.LLAMA_CPP) {
        RuntimeType.UNKNOWN
      } else {
        RuntimeType.LITERT_LM
      }
    val model =
      Model(
        name = info.fileName,
        url = info.url,
        configs = configs,
        sizeInBytes = info.fileSize,
        downloadFileName = info.fileName,
        showRunAgainButton = false,
        imported = true,
        llmSupportImage = llmSupportImage,
        llmSupportAudio = llmSupportAudio,
        llmSupportTinyGarden = llmSupportTinyGarden,
        llmSupportMobileActions = llmSupportMobileActions,
        capabilities = capabilities.toList(),
        capabilityToTaskTypes = capabilityToTaskTypes.toMap(),
        llmMaxToken = llmMaxToken,
        accelerators = accelerators,
        // We assume all imported models are LLM for now.
        isLlm = true,
        runtimeType = importedRuntimeType,
      )
    model.preProcess()

    return model
  }

  /**
   * The allowlist URL for a given app version. Kept as a copy of ModelManagerViewModel.kt's
   * file-scope `getAllowlistUrl` so the duplicated allowlist block here stays complete. Called by
   * [fetchModelAllowlist] below to fetch Google's current allowlist over the network, matching
   * upstream's ModelManagerViewModel.loadModelAllowlist() order.
   */
  private fun getAllowlistUrl(version: String): String {
    return "$ALLOWLIST_BASE_URL/${version}.json"
  }

  /**
   * Writes a freshly-fetched allowlist [content] to disk as [MODEL_ALLOWLIST_FILENAME], so a
   * later launch with no connectivity can fall back to the last-known-good network copy instead of
   * the (potentially stale) bundled asset. Copy of ModelManagerViewModel.kt's private
   * `saveModelAllowlistToDisk`, per the duplication note at the top of this file. A failed write is
   * logged and swallowed -- it must never prevent the allowlist that was just fetched from being
   * used this launch.
   */
  private fun saveModelAllowlistToDisk(content: String) {
    try {
      Log.d(TAG, "Saving model allowlist to disk...")
      val file = File(modelsDir, MODEL_ALLOWLIST_FILENAME)
      file.writeText(content)
      Log.d(TAG, "Done: saving model allowlist to disk.")
    } catch (e: Exception) {
      Log.e(TAG, "failed to write model allowlist to disk", e)
    }
  }

  private fun readModelAllowlistFromDisk(
    modelsDir: File,
    fileName: String = MODEL_ALLOWLIST_FILENAME,
  ): ModelAllowlist? {
    try {
      Log.d(TAG, "Reading model allowlist from disk: $fileName")
      val baseDir =
        if (fileName == MODEL_ALLOWLIST_TEST_FILENAME) File("/data/local/tmp") else modelsDir
      val file = File(baseDir, fileName)
      if (file.exists()) {
        val content = file.readText()
        Log.d(TAG, "Model allowlist content from local file: $content")

        val gson = Gson()
        return gson.fromJson(content, ModelAllowlist::class.java)
      }
    } catch (e: Exception) {
      Log.e(TAG, "failed to read model allowlist from disk", e)
      return null
    }

    return null
  }

  private fun readModelAllowlistFromAssets(context: Context): ModelAllowlist? {
    try {
      Log.d(TAG, "Reading model allowlist from assets...")
      val content =
        context.assets.open(MODEL_ALLOWLIST_FILENAME).bufferedReader().use { it.readText() }
      Log.d(TAG, "Model allowlist content from assets: $content")
      val gson = Gson()
      return gson.fromJson(content, ModelAllowlist::class.java)
    } catch (e: Exception) {
      Log.e(TAG, "failed to read model allowlist from assets", e)
      return null
    }
  }

  /**
   * Fetches/reads the model allowlist (from the test file on disk, a local test constant, the
   * network, the last-saved-to-disk copy, then the bundled assets copy, in that order) and returns
   * it parsed, or null if every source failed. Does no task/model-list mutation of its own -- that
   * is [applyModelAllowlist] below, called separately once the result here is non-null.
   *
   * Matches upstream's ModelManagerViewModel.loadModelAllowlist() order (test file, test constant,
   * network, disk cache) so the app gets Google's *current* allowlist instead of a copy frozen at
   * whatever version was bundled into this APK, with disk cache and then bundled assets as
   * fallbacks for a launch with no connectivity (the assets copy guarantees a first-ever launch,
   * with no cache yet, still has a list). The network leg is skipped entirely -- falling straight
   * through to disk cache / assets -- when [OfflineMode.isEnabled] is true, so Box's offline-only
   * setting still works; this checks the flag directly rather than calling
   * [OfflineMode.assertOnlineOrThrow], since offline mode here must mean "use local sources
   * quietly," not "throw and fail the whole allowlist load."
   *
   * Callers must already be off the main thread: this does blocking network I/O via
   * [getJsonResponse]. Verified caller: [runLoadModelAllowlist] invokes this synchronously inside
   * a `withContext(Dispatchers.IO)` block (see [loadModelAllowlist]), so no additional dispatcher
   * switch is added here.
   */
  private fun fetchModelAllowlist(context: Context, modelsDir: File): ModelAllowlist? {
    // Load model allowlist json.
    // Try to read the test allowlist first.
    Log.d(TAG, "Loading test model allowlist.")
    var modelAllowlist =
      readModelAllowlistFromDisk(modelsDir, fileName = MODEL_ALLOWLIST_TEST_FILENAME)

    // Local test only.
    if (TEST_MODEL_ALLOW_LIST.isNotEmpty()) {
      Log.d(TAG, "Loading local model allowlist for testing.")
      val gson = Gson()
      try {
        modelAllowlist = gson.fromJson(TEST_MODEL_ALLOW_LIST, ModelAllowlist::class.java)
      } catch (e: JsonSyntaxException) {
        Log.e(TAG, "Failed to parse local test json", e)
      }
    }

    if (modelAllowlist == null) {
      if (OfflineMode.isEnabled.value) {
        Log.d(TAG, "Offline mode enabled -- skipping network allowlist fetch")
      } else {
        val version = BuildConfig.VERSION_NAME.replace(".", "_")
        val url = getAllowlistUrl(version)
        Log.d(TAG, "Loading model allowlist from network. Url: $url")
        val data = getJsonResponse<ModelAllowlist>(url = url)
        modelAllowlist = data?.jsonObj

        if (modelAllowlist == null) {
          Log.w(TAG, "Failed to load model allowlist from network")
        } else {
          Log.d(TAG, "Done: loading model allowlist from network")
          saveModelAllowlistToDisk(content = data?.textContent ?: "{}")
        }
      }

      if (modelAllowlist == null) {
        Log.d(TAG, "Trying model allowlist disk cache")
        modelAllowlist = readModelAllowlistFromDisk(modelsDir)

        if (modelAllowlist == null) {
          // Last resort: bundled assets, so a first launch with no connectivity (and thus no disk
          // cache yet) still has a list.
          Log.w(TAG, "Failed to load model allowlist from disk cache. Trying bundled assets")
          modelAllowlist = readModelAllowlistFromAssets(context)
        }
      }
    }

    return modelAllowlist
  }

  /**
   * Converts an already-fetched/parsed [modelAllowlist] into live [Model]s, appends them to
   * [allowlistModels], and attaches each to its task(s) in [curTasks] (including the
   * `task.modelNames`-driven attachment pass). Pure/synchronous -- does no I/O and starts no
   * coroutine of its own, so a caller running this inside a `withContext(Dispatchers.Main)` block
   * (as [runLoadModelAllowlist] does, to satisfy the main-thread mutation requirement
   * `task.models`/`allowlistModels` readers depend on) keeps that guarantee.
   */
  private fun applyModelAllowlist(
    modelAllowlist: ModelAllowlist,
    curTasks: List<Task>,
    allowlistModels: MutableList<Model>,
  ) {
    val isAICoreAvailable by lazy {
      Log.d(TAG, "loadModelAllowlist: Checking AICore availability")
      // Build a fast-lookup set of all supported device models.
      // This extracts the models from all allowed groups, flattens them into a single stream,
      // lowercases them for case-insensitive matching, and stores them in a Set.
      val allowedDeviceModelsSet =
        modelAllowlist.aicoreRequirements
          ?.allowedDeviceGroups
          ?.asSequence()
          ?.flatMap { it.deviceModels }
          ?.map { it.lowercase() }
          ?.toSet()
      isAICoreSupported(allowedDeviceModelsSet)
    }

    // Convert models in the allowlist.
    Log.d(TAG, "loadModelAllowlist: Converting models. Total models: ${modelAllowlist.models.size}")
    val nameToModel = mutableMapOf<String, Model>()
    for (allowedModel in modelAllowlist.models) {
      if (allowedModel.disabled == true) {
        continue
      }

      if (allowedModel.runtimeType == RuntimeType.AICORE && !isAICoreAvailable) {
        continue
      }

      // Ignore the allowedModel if its accelerator is only npu and this device's soc is not in
      // its socToModelFiles.
      val accelerators = allowedModel.defaultConfig?.accelerators ?: ""
      val acceleratorList = accelerators.split(",").map { it.trim() }.filter { it.isNotEmpty() }
      if (acceleratorList.size == 1 && acceleratorList[0] == "npu") {
        val socToModelFiles = allowedModel.socToModelFiles
        if (socToModelFiles != null && !socToModelFiles.containsKey(SOC)) {
          Log.d(
            TAG,
            "Ignoring model '${allowedModel.name}' because it's NPU-only and not supported on SOC: $SOC",
          )
          continue
        }
      }

      val model = allowedModel.toModel()
      allowlistModels.add(model)
      nameToModel.put(model.name, model)
      for (taskType in allowedModel.taskTypes) {
        val task = curTasks.find { it.id == taskType }
        // Guard against duplicates when this runs more than once (e.g. activity recreation, or a
        // joined single-flight call). Tasks like WhisperTask/ImageGenTask pre-populate their
        // models list in the constructor, so we must not clear it wholesale.
        if (task != null && task.models.none { it.name == model.name }) {
          task.models.add(model)
        }

        if (task?.id == BuiltInTaskId.LLM_TINY_GARDEN) {
          val newConfigs = model.configs.toMutableList()
          newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
          model.configs = newConfigs
        }
      }
    }

    // Find models from allowlist if a task's `modelNames` field is not empty.
    Log.d(TAG, "loadModelAllowlist: Processing task modelNames")
    for (task in curTasks) {
      if (task.modelNames.isNotEmpty()) {
        for (modelName in task.modelNames) {
          val model = nameToModel[modelName]
          if (model == null) {
            Log.w(TAG, "Model '$modelName' in task '${task.label}' not found in allowlist.")
            continue
          }
          if (task.models.none { it.name == model.name }) {
            Log.d(TAG, "Adding model '$modelName' to task '${task.label}' from modelNames.")
            task.models.add(model)
          }
        }
      }
    }
  }

  private fun getCategoryLabel(context: Context, category: CategoryInfo): String {
    val stringRes = category.labelStringRes
    val label = category.label
    if (stringRes != null) {
      return context.getString(stringRes)
    } else if (label != null) {
      return label
    }
    return context.getString(R.string.category_unlabeled)
  }

  private fun groupTasksByCategory(context: Context, tasks: List<Task>): Map<String, List<Task>> {
    val categoryMap: Map<String, CategoryInfo> =
      tasks.associateBy { it.category.id }.mapValues { it.value.category }

    val groupedTasks = tasks.groupBy { it.category.id }
    val groupedSortedTasks: MutableMap<String, List<Task>> = mutableMapOf()
    // Sort the tasks in categories by pre-defined order. Sort other tasks by label.
    for (categoryId in groupedTasks.keys) {
      val sortedTasks =
        groupedTasks[categoryId]!!.sortedWith { a, b ->
          if (categoryId == Category.LLM.id) {
            val order: List<String> =
              when (categoryId) {
                Category.LLM.id -> PREDEFINED_LLM_TASK_ORDER
                else -> listOf()
              }
            val indexA = order.indexOf(a.id)
            val indexB = order.indexOf(b.id)
            if (indexA != -1 && indexB != -1) {
              indexA.compareTo(indexB)
            } else if (indexA != -1) {
              -1
            } else if (indexB != -1) {
              1
            } else {
              val ca = categoryMap[a.id]!!
              val cb = categoryMap[b.id]!!
              val caLabel = getCategoryLabel(context = context, category = ca)
              val cbLabel = getCategoryLabel(context = context, category = cb)
              caLabel.compareTo(cbLabel)
            }
          } else {
            a.label.compareTo(b.label)
          }
        }
      for ((index, task) in sortedTasks.withIndex()) {
        task.index = index
      }
      groupedSortedTasks[categoryId] = sortedTasks
    }

    return groupedSortedTasks
  }

  private fun isModelPartiallyDownloaded(context: Context, model: Model): Boolean {
    if (model.localModelFilePathOverride.isNotEmpty()) {
      return false
    }

    // A model is partially downloaded when the tmp file exists.
    val tmpFilePath =
      model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
    return File(tmpFilePath).exists()
  }

  /**
   * Retrieves the download status of a model.
   *
   * This function determines the download status of a given model by checking if it's fully
   * downloaded, partially downloaded, or not downloaded at all. It also retrieves the received and
   * total bytes for partially downloaded models.
   */
  private fun getModelDownloadStatus(
    context: Context,
    modelsDir: File,
    model: Model,
  ): ModelDownloadStatus {
    Log.d(TAG, "Checking model ${model.name} download status...")

    if (model.localFileRelativeDirPathOverride.isNotEmpty()) {
      Log.d(TAG, "Model has localFileRelativeDirPathOverride set. Set status to SUCCEEDED")
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = 0,
        totalBytes = 0,
      )
    }

    var status = ModelDownloadStatusType.NOT_DOWNLOADED
    var receivedBytes = 0L
    var totalBytes = 0L

    // Partially downloaded.
    if (isModelPartiallyDownloaded(context = context, model = model)) {
      status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
      val tmpFilePath =
        model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
      val tmpFile = File(tmpFilePath)
      receivedBytes = tmpFile.length()
      totalBytes = model.totalBytes
      Log.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
    }
    // Fully downloaded.
    else if (isModelDownloaded(modelsDir = modelsDir, model = model)) {
      status = ModelDownloadStatusType.SUCCEEDED
      Log.d(TAG, "${model.name} has been downloaded.")
    }
    // Not downloaded.
    else {
      Log.d(TAG, "${model.name} has not been downloaded.")
    }

    return ModelDownloadStatus(
      status = status,
      receivedBytes = receivedBytes,
      totalBytes = totalBytes,
    )
  }

  private fun isFileInModelsDir(modelsDir: File, fileName: String): Boolean {
    val file = File(modelsDir, fileName)
    return file.exists()
  }

  private fun deleteFileFromModelsDir(modelsDir: File, fileName: String) {
    if (isFileInModelsDir(modelsDir, fileName)) {
      val file = File(modelsDir, fileName)
      file.delete()
    }
  }

  /**
   * Deletes files from the model imports directory whose absolute paths start with a given prefix.
   */
  private fun deleteFilesFromImportDir(modelsDir: File, fileName: String) {
    val prefixAbsolutePath =
      "${modelsDir.absolutePath}${File.separator}$IMPORTS_DIR${File.separator}$fileName"
    val filesToDelete =
      File(modelsDir, IMPORTS_DIR).listFiles { dirFile, name ->
        File(dirFile, name).absolutePath.startsWith(prefixAbsolutePath)
      } ?: arrayOf()
    for (file in filesToDelete) {
      Log.d(TAG, "Deleting file: ${file.name}")
      file.delete()
    }
  }

  private fun deleteDirFromModelsDir(modelsDir: File, dir: String) {
    if (isFileInModelsDir(modelsDir, dir)) {
      val file = File(modelsDir, dir)
      file.deleteRecursively()
    }
  }

  private fun checkIfModelDownloaded(
    modelsDir: File,
    model: Model,
    version: String,
    fileName: String = model.downloadFileName,
  ): Boolean {
    val modelRelativePath =
      if (model.imported) {
        listOf(IMPORTS_DIR, fileName).joinToString(File.separator)
      } else {
        listOf(model.normalizedName, version, fileName).joinToString(File.separator)
      }
    val downloadedFileExists =
      fileName.isNotEmpty() &&
        ((model.localModelFilePathOverride.isEmpty() &&
          isFileInModelsDir(modelsDir, modelRelativePath)) ||
          (model.localModelFilePathOverride.isNotEmpty() &&
            File(model.localModelFilePathOverride).exists()))

    val unzippedDirectoryExists =
      model.isZip &&
        model.unzipDir.isNotEmpty() &&
        isFileInModelsDir(
          modelsDir,
          listOf(model.normalizedName, version, model.unzipDir).joinToString(File.separator),
        )

    return downloadedFileExists || unzippedDirectoryExists
  }

  private fun isModelDownloaded(modelsDir: File, model: Model): Boolean {
    model.updatable = false
    // First, check if the model with the current (latest) version has been downloaded.
    if (checkIfModelDownloaded(modelsDir, model, model.version)) return true

    // If not, check if any updatable model file (previous version) has been downloaded.
    for (updatableFile in model.updatableModelFiles) {
      if (updatableFile.commitHash.isEmpty()) continue
      if (
        checkIfModelDownloaded(modelsDir, model, updatableFile.commitHash, updatableFile.fileName)
      ) {
        // If an updatable version is found on the device, update the model's version and file name
        // to match the downloaded one, and mark it as updatable.
        model.version = updatableFile.commitHash
        model.downloadFileName = updatableFile.fileName
        model.updatable = true
        return true
      }
    }

    return false
  }
}

// Mirrors NotificationScheduleManagerEntryPoint (notifications/NotificationScheduleManager.kt)
// exactly -- the existing precedent in this tree for reaching a @Singleton from a component
// with no Activity/ViewModel to inject through (there: BootReceiver, a BroadcastReceiver; here:
// OpenAiServerService, a plain, non-@AndroidEntryPoint Service). Lets
// OpenAiServerService.onStartCommand() obtain ModelRegistry via
// EntryPointAccessors.fromApplication() with no Activity having ever run in this process.
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ModelRegistryEntryPoint {
  fun modelRegistry(): ModelRegistry
}
