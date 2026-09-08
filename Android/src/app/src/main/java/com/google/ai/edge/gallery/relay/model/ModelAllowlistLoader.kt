// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.common.isAICoreSupported
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.relay.security.OfflineMode
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val MODEL_ALLOWLIST_TEST_FILENAME = "model_allowlist_test.json"
private const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

private const val TEST_MODEL_ALLOW_LIST = ""

// Copy of ModelManagerViewModel.kt's private list; keep in sync with the disk-reader twin below.
private val PREDEFINED_LLM_TASK_ORDER =
  listOf(
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_ASK_IMAGE,
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_TINY_GARDEN,
    BuiltInTaskId.LLM_MOBILE_ACTIONS,
    BuiltInTaskId.MP_SCRAPBOOK,
  )

class ModelAllowlistLoader(
  private val context: Context,
  private val modelsDir: File,
  private val registryScope: CoroutineScope,
  private val taskCatalog: TaskCatalog,
) {

  private var _allowlistModels: MutableList<Model> = mutableListOf()
  val allowlistModels: List<Model>
    get() = _allowlistModels

  // Synchronized, not Mutex: the check-and-create decision below never suspends.
  private val allowlistLoadLock = Any()
  private var allowlistLoadDeferred: CompletableDeferred<String?>? = null

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

  private suspend fun runLoadModelAllowlist(): String? {
    val modelAllowlist =
      fetchModelAllowlist(context = context, modelsDir = modelsDir)
        ?: return "Failed to load model list"

    Log.d(TAG, "Allowlist: $modelAllowlist")

    val curTasks = taskCatalog.getActiveCustomTasks().map { it.task }

    withContext(Dispatchers.Main) {
      _allowlistModels.clear()

      applyModelAllowlist(
        modelAllowlist = modelAllowlist,
        curTasks = curTasks,
        allowlistModels = _allowlistModels,
      )

      Log.d(TAG, "loadModelAllowlist: Processing tasks")
      taskCatalog.processTasks()
    }

    return null
  }

  fun groupTasksByCategory(): Map<String, List<Task>> =
    groupTasksByCategory(
      context = context,
      tasks = taskCatalog.getActiveCustomTasks().map { it.task },
    )

  private fun getAllowlistUrl(version: String): String {
    return "$ALLOWLIST_BASE_URL/${version}.json"
  }

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

  // Private twin in ModelManagerViewModel.kt; diff both at every upstream sync or they drift.
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

  // Blocking network I/O: callers must already be off the main thread.
  private fun fetchModelAllowlist(context: Context, modelsDir: File): ModelAllowlist? {
    Log.d(TAG, "Loading test model allowlist.")
    var modelAllowlist =
      readModelAllowlistFromDisk(modelsDir, fileName = MODEL_ALLOWLIST_TEST_FILENAME)

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
          Log.w(TAG, "Failed to load model allowlist from disk cache. Trying bundled assets")
          modelAllowlist = readModelAllowlistFromAssets(context)
        }
      }
    }

    return modelAllowlist
  }

  private fun applyModelAllowlist(
    modelAllowlist: ModelAllowlist,
    curTasks: List<Task>,
    allowlistModels: MutableList<Model>,
  ) {
    val isAICoreAvailable by lazy {
      Log.d(TAG, "loadModelAllowlist: Checking AICore availability")
      val allowedDeviceModelsSet =
        modelAllowlist.aicoreRequirements
          ?.allowedDeviceGroups
          ?.asSequence()
          ?.flatMap { it.deviceModels }
          ?.map { it.lowercase() }
          ?.toSet()
      isAICoreSupported(allowedDeviceModelsSet)
    }

    Log.d(TAG, "loadModelAllowlist: Converting models. Total models: ${modelAllowlist.models.size}")
    val nameToModel = mutableMapOf<String, Model>()
    for (allowedModel in modelAllowlist.models) {
      if (allowedModel.disabled == true) {
        continue
      }

      if (allowedModel.runtimeType == RuntimeType.AICORE && !isAICoreAvailable) {
        continue
      }

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
}
