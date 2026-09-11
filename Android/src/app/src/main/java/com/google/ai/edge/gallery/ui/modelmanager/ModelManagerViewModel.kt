/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.ProjectConfig
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.common.isAICoreSupported
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAccessibility
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadInfo
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelFile
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.data.isManagedDownload
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
// relay: this fork's own code. See relay/modelmanager/ModelRegistry.kt.
import com.google.ai.edge.gallery.data.SD_IMPORTS_DIR
import com.google.ai.edge.gallery.relay.device.DeviceProfile
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.security.OfflineMode
import com.google.ai.edge.gallery.proto.AccessTokenData
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.runtime.aicore.AICoreModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlin.collections.sortedWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues

private const val TAG = "AGModelManagerViewModel"
private const val TEXT_INPUT_HISTORY_MAX_SIZE = 50
private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"
private const val MODEL_ALLOWLIST_TEST_FILENAME = "model_allowlist_test.json"
private const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"
private const val PLACEHOLDER_FILENAME = "placeholder"
private const val UNPACKED_FILE_EXT = "unpacked"

private const val TEST_MODEL_ALLOW_LIST = ""

enum class TokenStatus {
  NOT_STORED,
  EXPIRED,
  NOT_EXPIRED,
}

enum class TokenRequestResultType {
  FAILED,
  SUCCEEDED,
  USER_CANCELLED,
}

data class TokenStatusAndData(val status: TokenStatus, val data: AccessTokenData?)

data class TokenRequestResult(val status: TokenRequestResultType, val errorMessage: String? = null)

data class ModelManagerUiState(
  /** A list of tasks available in the application. */
  val tasks: List<Task>,

  /** Tasks grouped by category. */
  val tasksByCategory: Map<String, List<Task>>,

  /** A map that tracks the download status of each model, indexed by model name. */
  val modelDownloadStatus: Map<String, ModelDownloadStatus>,

  /** Whether the app is loading and processing the model allowlist. */
  val loadingModelAllowlist: Boolean = true,

  /** The error message when loading the model allowlist. */
  val loadingModelAllowlistError: String = "",

  /** The currently selected model. */
  val selectedModel: Model = EMPTY_MODEL,

  /** The history of text inputs entered by the user. */
  val textInputHistory: List<String> = listOf(),
  val configValuesUpdateTrigger: Long = 0L,
  // Updated when model is imported of an imported model is deleted.
  val modelImportingUpdateTrigger: Long = 0L,

  /**
   * A map that tracks whether optional components are enabled for download for each model, indexed
   * by model name.
   */
  val downloadOptionalComponents: Map<String, Boolean> = mapOf(),

  /** Name of a model whose download exceeds free storage and awaits user confirmation. */
  val modelNeedingStorageConfirmation: String? = null,

  /** A map that tracks the download status of optional extra data files, indexed by model name. */
  val extraDataDownloadStatus: Map<String, ModelDownloadStatus> = mapOf(),
) {
  fun isModelInitialized(model: Model): Boolean {
    return model.initStatusFlow.value is Model.InitializationStatus.Initialized
  }

  fun isModelInitializing(model: Model): Boolean {
    return model.initializing
  }

  fun isDownloadOptionalComponentsEnabled(modelName: String): Boolean {
    return downloadOptionalComponents[modelName] ?: true
  }

  fun getExtraDataDownloadStatus(modelName: String): ModelDownloadStatus? {
    return extraDataDownloadStatus[modelName]
  }
}

private val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )
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

/**
 * ViewModel responsible for managing models, their download status, and initialization.
 *
 * This ViewModel handles model-related operations such as downloading, deleting, initializing, and
 * cleaning up models. It also manages the UI state for model management, including the list of
 * tasks, models, download statuses, and initialization statuses.
 */
@HiltViewModel
open class ModelManagerViewModel
@Inject
constructor(
  private val downloadRepository: DownloadRepository,
  val dataStoreRepository: DataStoreRepository,
  private val lifecycleProvider: AppLifecycleProvider,
  private val systemPromptRepository: SystemPromptRepository,
  private val modelRegistry: ModelRegistry,
  val huggingFaceApiClient: HuggingFaceApiClient,
  private val deviceProfile: DeviceProfile,
  @ApplicationContext private val context: Context,
) :
  ViewModel()
{

  private val modelsDir = getModelStorageDir(context)
  protected val _uiState = MutableStateFlow(createEmptyUiState())
  open val uiState = _uiState.asStateFlow()

  fun fetchModelDetails(modelId: String, onResult: (HfModelItemProto?) -> Unit) {
    viewModelScope.launch {
      try {
        val token = getTokenStatusAndData().data?.accessToken
        val details = huggingFaceApiClient.getModelDetails(modelId, accessToken = token)
        onResult(details)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to fetch model details for $modelId", e)
        onResult(null)
      }
    }
  }

  // relay: the allowlist and the per-model initialized-backend set are owned by the process-scoped
  // ModelRegistry (relay/modelmanager/ModelRegistry.kt) so the API server and BootReceiver see the
  // same state without an Activity. Google's own fields below stay in place, unread.
  private var _allowlistModels: MutableList<Model> = mutableListOf()
  val allowlistModels: List<Model>
    get() = modelRegistry.allowlistModels

  private val initializedBackends = mutableMapOf<String, MutableSet<String>>()

  fun isFirstInitialization(model: Model): Boolean = modelRegistry.isFirstInitialization(model)

  val authService = AuthorizationService(context)
  var curAccessToken: String = ""

  open fun isDownloadOptionalComponentsEnabled(modelName: String): Boolean {
    return _uiState.value.isDownloadOptionalComponentsEnabled(modelName)
  }

  open fun setDownloadOptionalComponents(modelName: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val newMap = currentState.downloadOptionalComponents.toMutableMap()
      newMap[modelName] = enabled
      currentState.copy(downloadOptionalComponents = newMap)
    }
  }

  override fun onCleared() {
    authService.dispose()
  }

  fun getTaskById(id: String): Task? {
    return uiState.value.tasks.find { it.id == id }
  }

  fun getTasksByIds(ids: Set<String>): List<Task> {
    return uiState.value.tasks.filter { ids.contains(it.id) }
  }

  fun getCustomTaskByTaskId(id: String): CustomTask? {
    return getActiveCustomTasks().find { it.task.id == id }
  }

  // relay: the @IntoSet task providers are unscoped (module-scoped @InstallIn, not
  // @Singleton), so they re-run per injection point. Injecting the Set<CustomTask> here
  // would build a second, parallel task graph that ModelRegistry's attachment work
  // (allowlist, imported models, preProcess) never touches. Delegate to the registry's
  // copy so the whole app shares one set of Task objects.
  fun getActiveCustomTasks(): List<CustomTask> {
    return modelRegistry.getActiveCustomTasks()
  }

  fun getSelectedModel(): Model? {
    return uiState.value.selectedModel
  }

  open fun getModelByName(name: String): Model? {
    for (task in uiState.value.tasks) {
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
    for (task in uiState.value.tasks) {
      for (model in task.models) {
        allModels.add(model)
      }
    }
    return allModels.toList().sortedBy { it.displayName.ifEmpty { it.name } }
  }

  open fun getAllDownloadedModels(): List<Model> {
    return getAllModels().filter {
      uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED &&
        it.isLlm
    }
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

  fun updateConfigValuesUpdateTrigger() {
    _uiState.update { it.copy(configValuesUpdateTrigger = System.currentTimeMillis()) }
  }

  fun selectModel(model: Model) {
    if (_uiState.value.selectedModel.name != model.name) {
      _uiState.update { it.copy(selectedModel = model) }
    }
  }

  open fun downloadModel(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean = isDownloadOptionalComponentsEnabled(model.name),
    bypassStorageCheck: Boolean = false,
  ) {
    // Choke point for the storage check: a size of 0 means unknown/unresolved and must never
    // warn, or every auto-started import (unresolved size at start) would prompt spuriously.
    if (
      !bypassStorageCheck &&
        model.downloadInfo.totalBytes > 0L &&
        model.downloadInfo.totalBytes > deviceProfile.freeStorageBytes()
    ) {
      _uiState.update { it.copy(modelNeedingStorageConfirmation = model.name) }
      return
    }

    // Update status.
    setDownloadStatus(
      curModel = model,
      status = ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS),
    )

    if (model.runtimeType == RuntimeType.AICORE) {
      AICoreModelHelper.downloadModel(
        context = context,
        coroutineScope = viewModelScope,
        model = model,
        onProgress = { downloaded: Long, total: Long ->
          setDownloadStatus(
            curModel = model,
            status =
              ModelDownloadStatus(
                status = ModelDownloadStatusType.IN_PROGRESS,
                receivedBytes = downloaded,
                totalBytes = total,
              ),
          )
        },
        onDone = {
          setDownloadStatus(
            curModel = model,
            status =
              ModelDownloadStatus(
                status = ModelDownloadStatusType.SUCCEEDED,
                receivedBytes = model.downloadInfo.sizeInBytes,
                totalBytes = model.downloadInfo.sizeInBytes,
              ),
          )
        },
        onError = { error: String ->
          setDownloadStatus(
            curModel = model,
            status =
              ModelDownloadStatus(status = ModelDownloadStatusType.FAILED, errorMessage = error),
          )
        },
      )
      return
    }

    // Delete the model files first.
    deleteModel(
      model = model,
      removeImportedFromModelList = false,
      preserveOptionalComponentsState = true,
    )

    val family = getModelFamily(model)
    val isExtraDataAlreadyDownloaded =
      family.any {
        uiState.value.extraDataDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
      } || family.any { isExtraDataPresentOnDisk(it, task?.id) }
    val actualIncludeExtraDataFiles = includeExtraDataFiles && !isExtraDataAlreadyDownloaded

    // Start to send download request.
    downloadRepository.downloadModel(
      task = task,
      model = model,
      includeExtraDataFiles = actualIncludeExtraDataFiles,
      onStatusUpdated = this::setDownloadStatus,
    )
  }

  /** Clears a pending storage-confirmation prompt without starting the download. */
  fun dismissStorageConfirmation() {
    _uiState.update { it.copy(modelNeedingStorageConfirmation = null) }
  }

  /** User chose "proceed anyway" on the storage warning; start the download regardless. */
  fun proceedWithDownloadDespiteStorageWarning(model: Model, task: Task? = null) {
    _uiState.update { it.copy(modelNeedingStorageConfirmation = null) }
    downloadModel(task = task, model = model, bypassStorageCheck = true)
  }

  fun cancelDownloadModel(model: Model) {
    // AICore models cannot be deleted from the download repository within the app.
    if (!model.isManagedDownload) {
      return
    }
    downloadRepository.cancelDownloadModel(model)
    deleteModel(model = model, removeImportedFromModelList = false)
  }

  private fun isExtraDataPresentOnDisk(model: Model, taskId: String? = null): Boolean {
    val extraFiles = model.downloadInfo.extraDataFiles(taskId)
    if (extraFiles.isEmpty()) return false
    val modelDir =
      File(model.getPath(context = context, fileName = PLACEHOLDER_FILENAME)).parentFile
        ?: File(model.getPath(context = context, fileName = PLACEHOLDER_FILENAME))
    if (!modelDir.exists()) return false

    return extraFiles.any { extraFile ->
      val directFile = File(modelDir, extraFile.downloadFileName)
      if (directFile.exists()) return@any true
      val nameFile = File(modelDir, extraFile.name)
      if (nameFile.exists()) return@any true
      val folderName = extraFile.downloadFileName.substringBeforeLast(".")
      val dirFile = File(modelDir, folderName)
      if (dirFile.exists()) return@any true
      false
    }
  }

  fun getModelFamily(model: Model, modelVariants: List<Model> = emptyList()): List<Model> {
    val rootName = model.parentModelName ?: model.name
    val allModels = (listOf(model) + modelVariants + getAllModels()).distinctBy { it.name }
    val family = allModels.filter { it.name == rootName || it.parentModelName == rootName }
    return family.ifEmpty { listOf(model) }
  }

  fun syncExtraDataAcrossFamily(model: Model, modelVariants: List<Model> = emptyList()) {
    val family = getModelFamily(model, modelVariants)
    val sourceModel = family.firstOrNull { isExtraDataPresentOnDisk(it) } ?: return
    val srcModelDir =
      File(sourceModel.getPath(context = context, fileName = PLACEHOLDER_FILENAME)).parentFile
        ?: File(sourceModel.getPath(context = context, fileName = PLACEHOLDER_FILENAME))
    if (!srcModelDir.exists()) return
    for (targetVariant in family) {
      if (
        targetVariant.name != sourceModel.name &&
          (uiState.value.modelDownloadStatus[targetVariant.name]?.status ==
            ModelDownloadStatusType.SUCCEEDED || targetVariant.name == model.name)
      ) {
        val destModelDir =
          File(targetVariant.getPath(context = context, fileName = PLACEHOLDER_FILENAME)).parentFile
            ?: File(targetVariant.getPath(context = context, fileName = PLACEHOLDER_FILENAME))
        if (srcModelDir.absolutePath != destModelDir.absolutePath) {
          if (!destModelDir.exists()) destModelDir.mkdirs()
          for (extraFile in sourceModel.downloadInfo.extraDataFiles) {
            val folderName = extraFile.downloadFileName.substringBeforeLast(".")
            val candidates =
              listOf(
                extraFile.downloadFileName,
                "${extraFile.downloadFileName}.$UNPACKED_FILE_EXT",
                folderName,
                extraFile.name,
              )
            for (candidate in candidates) {
              val srcFile = File(srcModelDir, candidate)
              val destFile = File(destModelDir, candidate)
              if (srcFile.exists() && !destFile.exists()) {
                try {
                  srcFile.copyRecursively(destFile, overwrite = false)
                } catch (e: Exception) {
                  Log.w(TAG, "Failed to sync extra data file $candidate: ${e.message}")
                }
              }
            }
          }
        }
      }
    }
  }

  fun setExtraDataDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    _uiState.update { currentState ->
      val curStatus = currentState.extraDataDownloadStatus.toMutableMap()
      curStatus[curModel.name] = status
      currentState.copy(extraDataDownloadStatus = curStatus)
    }
  }

  open fun downloadExtraDataFiles(
    task: Task?,
    model: Model,
    modelVariants: List<Model> = emptyList(),
  ) {
    val family = getModelFamily(model, modelVariants)
    for (m in family) {
      setDownloadOptionalComponents(m.name, true)
    }
    val downloadedModels = family.filter {
      uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }
    val targetModel = downloadedModels.firstOrNull() ?: model
    val extraFiles = targetModel.downloadInfo.extraDataFiles(task?.id)
    if (extraFiles.isEmpty()) return
    val totalBytes = extraFiles.sumOf { it.sizeInBytes }
    val initialStatus =
      ModelDownloadStatus(
        status = ModelDownloadStatusType.IN_PROGRESS,
        receivedBytes = 0L,
        totalBytes = totalBytes,
      )
    for (m in family) {
      setExtraDataDownloadStatus(curModel = m, status = initialStatus)
    }
    downloadRepository.downloadExtraDataFiles(
      task = task,
      model = targetModel,
      onStatusUpdated = { _, status ->
        if (status.status == ModelDownloadStatusType.SUCCEEDED) {
          syncExtraDataAcrossFamily(targetModel, family)
        }
        for (m in family) {
          setExtraDataDownloadStatus(m, status)
        }
      },
    )
  }

  fun cancelDownloadExtraDataFiles(model: Model, modelVariants: List<Model> = emptyList()) {
    val family = getModelFamily(model, modelVariants)
    for (m in family) {
      downloadRepository.cancelDownloadExtraDataFiles(m)
      val modelDir =
        File(m.getPath(context = context, fileName = PLACEHOLDER_FILENAME)).parentFile
          ?: File(m.getPath(context = context, fileName = PLACEHOLDER_FILENAME))
      if (modelDir.exists()) {
        for (extraFile in m.downloadInfo.extraDataFiles) {
          val tmpFile = File(modelDir, "${extraFile.downloadFileName}.$TMP_FILE_EXT")
          if (tmpFile.exists()) tmpFile.delete()
        }
      }
      setExtraDataDownloadStatus(
        curModel = m,
        status = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
      )
    }
  }

  open fun deleteExtraDataFiles(
    model: Model,
    task: Task? = null,
    modelVariants: List<Model> = emptyList(),
    onComplete: (() -> Unit)? = null,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      deleteExtraDataFilesInternal(model, task, modelVariants)
      withContext(Dispatchers.Main) { onComplete?.invoke() }
    }
  }

  internal fun deleteExtraDataFilesInternal(
    model: Model,
    task: Task? = null,
    modelVariants: List<Model> = emptyList(),
  ) {
    val family = getModelFamily(model, modelVariants)
    for (m in family) {
      for (curTask in uiState.value.tasks) {
        if (task == null || curTask.id == task.id) {
          if (curTask.models.any { it.name == m.name }) {
            val customTask = getCustomTaskByTaskId(id = curTask.id)
            customTask?.onDeleteExtraDataFn(context = context, model = m)
          }
        }
      }

      val modelDir =
        File(m.getPath(context = context, fileName = PLACEHOLDER_FILENAME)).parentFile
          ?: File(m.getPath(context = context, fileName = PLACEHOLDER_FILENAME))
      if (modelDir.exists()) {
        val extraFiles = m.downloadInfo.extraDataFiles(task?.id)
        for (extraFile in extraFiles) {
          val directFile = File(modelDir, extraFile.downloadFileName)
          if (directFile.exists()) directFile.deleteRecursively()
          val tmpFile = File(modelDir, "${extraFile.downloadFileName}.$TMP_FILE_EXT")
          if (tmpFile.exists()) tmpFile.delete()
          val markerFile = File(modelDir, "${extraFile.downloadFileName}.$UNPACKED_FILE_EXT")
          if (markerFile.exists()) markerFile.delete()
          val folderName = extraFile.downloadFileName.substringBeforeLast(".")
          val dirFile = File(modelDir, folderName)
          if (dirFile.exists()) dirFile.deleteRecursively()
          val nameFile = File(modelDir, extraFile.name)
          if (nameFile.exists()) nameFile.deleteRecursively()
        }
      }

      setDownloadOptionalComponents(m.name, false)
      setExtraDataDownloadStatus(
        curModel = m,
        status = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
      )
    }
  }

  fun deleteModel(
    model: Model,
    removeImportedFromModelList: Boolean = true,
    preserveOptionalComponentsState: Boolean = false,
  ) {
    // relay: a delete must not orphan a live native engine the API server is serving. Unpin it,
    // tear down any live instance, and drop the registry's initialized-backend memory for it.
    modelRegistry.releaseHold(model.name, "api")
    modelRegistry.forgetInitializedBackends(model.name)
    if (model.instance != null) {
      uiState.value.tasks
        .find { it.models.contains(model) }
        ?.let { cleanupModel(context = context, task = it, model = model) }
    }

    for (curTask in uiState.value.tasks) {
      if (curTask.models.any { it.name == model.name }) {
        val customTask = getCustomTaskByTaskId(id = curTask.id)
        customTask?.onDeleteModelFn(context = context, model = model)
      }
    }

    val family = getModelFamily(model)
    val remainingDownloadedVariants = family.filter {
      it.name != model.name &&
        uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }

    if (remainingDownloadedVariants.isNotEmpty() && !model.downloadInfo.imported) {
      val targetVariant = remainingDownloadedVariants.first()
      val srcModelDir =
        File(model.getPath(context = context, fileName = PLACEHOLDER_FILENAME)).parentFile
          ?: File(model.getPath(context = context, fileName = PLACEHOLDER_FILENAME))
      val destModelDir =
        File(targetVariant.getPath(context = context, fileName = PLACEHOLDER_FILENAME)).parentFile
          ?: File(targetVariant.getPath(context = context, fileName = PLACEHOLDER_FILENAME))
      if (srcModelDir.exists() && srcModelDir.absolutePath != destModelDir.absolutePath) {
        if (!destModelDir.exists()) destModelDir.mkdirs()
        for (extraFile in model.downloadInfo.extraDataFiles) {
          val folderName = extraFile.downloadFileName.substringBeforeLast(".")
          val candidates =
            listOf(
              extraFile.downloadFileName,
              "${extraFile.downloadFileName}.$UNPACKED_FILE_EXT",
              folderName,
              extraFile.name,
            )
          for (candidate in candidates) {
            val srcFile = File(srcModelDir, candidate)
            val destFile = File(destModelDir, candidate)
            if (srcFile.exists() && !destFile.exists()) {
              try {
                srcFile.copyRecursively(destFile, overwrite = false)
              } catch (e: Exception) {
                Log.w(TAG, "Failed to migrate extra data file $candidate: ${e.message}")
              }
            }
          }
        }
      }
    }

    if (model.downloadInfo.imported) {
      deleteFilesFromImportDir(model.downloadInfo.downloadFileName)
    } else {
      deleteDirFromModelsDir(model.normalizedName)
    }

    initializedBackends.remove(model.name)

    // Delete model from the list if model is imported as a local model and
    // removeImportedFromModelList is
    // true.
    if (model.downloadInfo.imported && removeImportedFromModelList) {
      for (curTask in uiState.value.tasks) {
        val index = curTask.models.indexOf(model)
        if (index >= 0) {
          curTask.models.removeAt(index)
        }
        curTask.updateTrigger.value = System.currentTimeMillis()
      }

      // Update data store.
      val importedModels = dataStoreRepository.readImportedModels().toMutableList()
      val importedModelIndex = importedModels.indexOfFirst { it.fileName == model.name }
      if (importedModelIndex >= 0) {
        importedModels.removeAt(importedModelIndex)
      }
      dataStoreRepository.saveImportedModels(importedModels = importedModels)
    }
    _uiState.update { currentState ->
      val curModelDownloadStatus = currentState.modelDownloadStatus.toMutableMap()
      if (model.downloadInfo.imported && removeImportedFromModelList) {
        curModelDownloadStatus.remove(model.name)
      } else {
        curModelDownloadStatus[model.name] =
          ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
      }
      val updatedDownloadOptionalComponents = currentState.downloadOptionalComponents.toMutableMap()
      val curExtraDownloadStatus = currentState.extraDataDownloadStatus.toMutableMap()
      if (!preserveOptionalComponentsState) {
        if (remainingDownloadedVariants.isEmpty()) {
          for (m in family) {
            updatedDownloadOptionalComponents.remove(m.name)
            curExtraDownloadStatus.remove(m.name)
          }
        } else {
          updatedDownloadOptionalComponents.remove(model.name)
          curExtraDownloadStatus.remove(model.name)
        }
      }
      currentState.copy(
        modelDownloadStatus = curModelDownloadStatus,
        downloadOptionalComponents = updatedDownloadOptionalComponents,
        extraDataDownloadStatus = curExtraDownloadStatus,
        tasks = currentState.tasks.toList(),
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }
  }

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) {
    // relay: the model lifecycle is owned by the process-scoped ModelRegistry so the API server
    // (relay/openai/) and this Activity share one engine, one accelerator record and one cleanup
    // handshake. Google's body moved verbatim to ModelRegistry.initializeModel.
    modelRegistry.initializeModel(
      context = context,
      task = task,
      model = model,
      force = force,
      onDone = onDone,
      onError = onError,
    )
  }

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) {
    // relay: see initializeModel above -- ModelRegistry.cleanupModel holds the per-model cleanup
    // deferred the API server awaits before reusing a name.
    modelRegistry.cleanupModel(
      context = context,
      task = task,
      model = model,
      instanceToCleanUp = instanceToCleanUp,
      onDone = onDone,
    )
  }

  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    // Delete downloaded file if status is failed or not_downloaded.
    if (
      status.status == ModelDownloadStatusType.FAILED ||
        status.status == ModelDownloadStatusType.NOT_DOWNLOADED
    ) {
      deleteFileFromModelsDir(curModel.downloadInfo.downloadFileName)
    }

    if (status.status == ModelDownloadStatusType.SUCCEEDED) {
      syncExtraDataAcrossFamily(curModel)
      val family = getModelFamily(curModel)
      if (
        (isDownloadOptionalComponentsEnabled(curModel.name) &&
          curModel.downloadInfo.extraDataFiles.isNotEmpty()) ||
          family.any { isExtraDataPresentOnDisk(it) }
      ) {
        val succeededStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
        for (m in family) {
          setExtraDataDownloadStatus(curModel = m, status = succeededStatus)
        }
      }
    }

    _uiState.update { currentState ->
      val curModelDownloadStatus = currentState.modelDownloadStatus.toMutableMap()
      curModelDownloadStatus[curModel.name] = status
      currentState.copy(modelDownloadStatus = curModelDownloadStatus)
    }
  }

  fun addTextInputHistory(text: String) {
    if (uiState.value.textInputHistory.indexOf(text) < 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.add(0, text)
      if (newHistory.size > TEXT_INPUT_HISTORY_MAX_SIZE) {
        newHistory.removeAt(newHistory.size - 1)
      }
      _uiState.update { it.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    } else {
      promoteTextInputHistoryItem(text)
    }
  }

  fun promoteTextInputHistoryItem(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      newHistory.add(0, text)
      _uiState.update { it.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun deleteTextInputHistory(text: String) {
    val index = uiState.value.textInputHistory.indexOf(text)
    if (index >= 0) {
      val newHistory = uiState.value.textInputHistory.toMutableList()
      newHistory.removeAt(index)
      _uiState.update { it.copy(textInputHistory = newHistory) }
      dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
    }
  }

  fun clearTextInputHistory() {
    _uiState.update { it.copy(textInputHistory = mutableListOf()) }
    dataStoreRepository.saveTextInputHistory(_uiState.value.textInputHistory)
  }

  fun readThemeOverride(): Theme {
    return dataStoreRepository.readTheme()
  }

  fun saveThemeOverride(theme: Theme) {
    dataStoreRepository.saveTheme(theme = theme)
  }

  /**
   * Checks the accessibility of a remote model URL.
   *
   * @param model The model to probe.
   * @param accessToken Optional Hugging Face or server access token.
   * @return [ModelAccessibility] indicating if the model URL is accessible, gated, or needs auth.
   */
  suspend fun checkModelAccessibility(
    model: Model,
    accessToken: String? = null,
  ): ModelAccessibility =
    withContext(Dispatchers.IO) {
      if (model.downloadInfo.url.isEmpty()) {
        return@withContext ModelAccessibility.ACCESSIBLE
      }
      // If it's a Hugging Face URL, delegate to HuggingFaceApiClient.
      if (HuggingFaceApiClient.isHuggingFaceUrl(model.downloadInfo.url)) {
        return@withContext huggingFaceApiClient.checkModelAccessibility(
          modelUrl = model.downloadInfo.url,
          accessToken = accessToken,
        )
      }

      val responseCode: Int
      try {
        OfflineMode.assertOnlineOrThrow()
        val url = URL(model.downloadInfo.url)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connect()

        responseCode = connection.responseCode
      } catch (e: Exception) {
        Log.e(TAG, "Error checking model accessibility for '${model.name}'", e)
        return@withContext ModelAccessibility.ERROR
      }

      when (responseCode) {
        in 200..299 -> ModelAccessibility.ACCESSIBLE
        HttpURLConnection.HTTP_UNAUTHORIZED -> ModelAccessibility.NEEDS_TOKEN_EXCHANGE
        HttpURLConnection.HTTP_FORBIDDEN -> ModelAccessibility.GATED
        else -> {
          Log.w(
            TAG,
            "Unexpected response code checking model accessibility for '${model.name}': $responseCode",
          )
          ModelAccessibility.ERROR
        }
      }
    }

  // Registration lives in ModelRegistry/ImportedModelStore; only ViewModel-owned uiState and the
  // download kickoff stay here.
  fun addImportedLlmModel(info: ImportedModel) {
    val model = modelRegistry.addImportedLlmModel(info = info)

    val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    if (model.downloadInfo.url.isNotEmpty()) {
      modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
    } else {
      modelDownloadStatus[model.name] =
        ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = info.fileSize,
          totalBytes = info.fileSize,
        )
    }

    _uiState.update {
      it.copy(
        tasks = it.tasks.toList(),
        modelDownloadStatus = modelDownloadStatus,
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }

    // A local file import has no URL and is already SUCCEEDED above; only a real
    // remote import needs the download kicked off automatically. Main dispatcher is required:
    // downloadModel observes WorkManager LiveData, and this runs on IO for a web import.
    if (model.downloadInfo.url.isNotEmpty()) {
      viewModelScope.launch(Dispatchers.Main) { downloadModel(task = null, model = model) }
    }
  }

  // Box: imported Stable-Diffusion GGUF models. The Model factory itself lives in
  // relay/modelmanager/ModelRegistry.kt so the API server and the UI build identical SD models.
  //
  // [url] empty (default) is the original local-file import: the file already exists on disk, so
  // status is SUCCEEDED immediately. A non-empty [url] (Discovery import) instead kicks off a real
  // download, mirroring addImportedLlmModel's url-vs-local branch above.
  fun addImportedSdModel(fileName: String, fileSize: Long, url: String = "") {
    val model =
      modelRegistry.createImportedSdModel(fileName = fileName, fileSize = fileSize, url = url)

    val task = getTasksByIds(ids = setOf(BuiltInTaskId.IMAGE_GEN)).firstOrNull() ?: return
    val existingIndex = task.models.indexOfFirst { it.name == model.name && it.downloadInfo.imported }
    if (existingIndex >= 0) task.models.removeAt(existingIndex)
    task.models.add(model)
    model.preProcess()
    task.updateTrigger.value = System.currentTimeMillis()

    val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    modelDownloadStatus[model.name] =
      if (model.downloadInfo.url.isNotEmpty()) {
        getModelDownloadStatus(model = model)
      } else {
        ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = fileSize,
          totalBytes = fileSize,
        )
      }

    _uiState.update {
      uiState.value.copy(
        tasks = uiState.value.tasks.toList(),
        modelDownloadStatus = modelDownloadStatus,
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }

    if (model.downloadInfo.url.isNotEmpty()) {
      viewModelScope.launch(Dispatchers.Main) { downloadModel(task = null, model = model) }
    }
  }

  fun getTokenStatusAndData(): TokenStatusAndData {
    // Try to load token data from DataStore.
    var tokenStatus = TokenStatus.NOT_STORED
    Log.d(TAG, "Reading token data from data store...")
    val tokenData = dataStoreRepository.readAccessTokenData()

    // Token exists.
    if (tokenData != null && tokenData.accessToken.isNotEmpty()) {
      Log.d(TAG, "Token exists and loaded.")

      // Check expiration (with 5-minute buffer).
      val curTs = System.currentTimeMillis()
      val expirationTs = tokenData.expiresAtMs - 5 * 60
      Log.d(
        TAG,
        "Checking whether token has expired or not. Current ts: $curTs, expires at: $expirationTs",
      )
      if (curTs >= expirationTs) {
        Log.d(TAG, "Token expired!")
        tokenStatus = TokenStatus.EXPIRED
      } else {
        Log.d(TAG, "Token not expired.")
        tokenStatus = TokenStatus.NOT_EXPIRED
        curAccessToken = tokenData.accessToken
      }
    } else {
      Log.d(TAG, "Token doesn't exists.")
    }

    return TokenStatusAndData(status = tokenStatus, data = tokenData)
  }

  fun getAuthorizationRequest(): AuthorizationRequest {
    return AuthorizationRequest.Builder(
        ProjectConfig.authServiceConfig,
        ProjectConfig.clientId,
        ResponseTypeValues.CODE,
        ProjectConfig.redirectUri.toUri(),
      )
      .setScope("read-repos")
      .build()
  }

  fun handleAuthResult(result: ActivityResult, onTokenRequested: (TokenRequestResult) -> Unit) {
    val dataIntent = result.data
    if (dataIntent == null) {
      onTokenRequested(
        TokenRequestResult(
          status = TokenRequestResultType.FAILED,
          errorMessage = "Empty auth result",
        )
      )
      return
    }

    val response = AuthorizationResponse.fromIntent(dataIntent)
    val exception = AuthorizationException.fromIntent(dataIntent)

    when {
      response?.authorizationCode != null -> {
        // Authorization successful, exchange the code for tokens
        var errorMessage: String? = null
        authService.performTokenRequest(response.createTokenExchangeRequest()) {
          tokenResponse,
          tokenEx ->
          if (tokenResponse != null) {
            if (tokenResponse.accessToken == null) {
              errorMessage = "Empty access token"
            } else if (tokenResponse.refreshToken == null) {
              errorMessage = "Empty refresh token"
            } else if (tokenResponse.accessTokenExpirationTime == null) {
              errorMessage = "Empty expiration time"
            } else {
              // Token exchange successful. Store the tokens securely
              Log.d(TAG, "Token exchange successful. Storing tokens...")
              saveAccessToken(
                accessToken = tokenResponse.accessToken!!,
                refreshToken = tokenResponse.refreshToken!!,
                expiresAt = tokenResponse.accessTokenExpirationTime!!,
              )
              curAccessToken = tokenResponse.accessToken!!
              Log.d(TAG, "Token successfully saved.")
            }
          } else if (tokenEx != null) {
            errorMessage = "Token exchange failed: ${tokenEx.message}"
          } else {
            errorMessage = "Token exchange failed"
          }
          if (errorMessage == null) {
            onTokenRequested(TokenRequestResult(status = TokenRequestResultType.SUCCEEDED))
          } else {
            onTokenRequested(
              TokenRequestResult(
                status = TokenRequestResultType.FAILED,
                errorMessage = errorMessage,
              )
            )
          }
        }
      }

      exception != null -> {
        onTokenRequested(
          TokenRequestResult(
            status =
              if (exception.message == "User cancelled flow") TokenRequestResultType.USER_CANCELLED
              else TokenRequestResultType.FAILED,
            errorMessage = exception.message,
          )
        )
      }

      else -> {
        onTokenRequested(TokenRequestResult(status = TokenRequestResultType.USER_CANCELLED))
      }
    }
  }

  fun saveAccessToken(accessToken: String, refreshToken: String, expiresAt: Long) {
    dataStoreRepository.saveAccessTokenData(
      accessToken = accessToken,
      refreshToken = refreshToken,
      expiresAt = expiresAt,
    )
  }

  fun clearAccessToken() {
    dataStoreRepository.clearAccessTokenData()
  }

  private fun checkAICoreModelStatuses() {
    viewModelScope.launch(Dispatchers.Main) {
      val aicoreModels =
        uiState.value.tasks
          .flatMap { it.models }
          .filter { it.runtimeType == RuntimeType.AICORE }
          .distinctBy { it.name }

      // Proactively attempt AICore model download upon app startup.
      for (model in aicoreModels) {
        downloadModel(task = null, model = model)
      }
    }
  }

  private fun processPendingDownloads() {
    // Cancel all pending downloads for the retrieved models.
    downloadRepository.cancelAll {
      Log.d(TAG, "All workers are cancelled.")

      viewModelScope.launch(Dispatchers.Main) {
        val checkedModelNames = mutableSetOf<String>()
        val tokenStatusAndData = getTokenStatusAndData()
        for (task in uiState.value.tasks) {
          for (model in task.models) {
            if (checkedModelNames.contains(model.name)) {
              continue
            }

            // Start download for partially downloaded models.
            val downloadStatus = uiState.value.modelDownloadStatus[model.name]?.status
            if (downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED) {
              if (
                tokenStatusAndData.status == TokenStatus.NOT_EXPIRED &&
                  tokenStatusAndData.data != null
              ) {
                model.downloadInfo.accessToken = tokenStatusAndData.data.accessToken
              }
              Log.d(TAG, "Sending a new download request for '${model.name}'")
              downloadRepository.downloadModel(
                task = task,
                model = model,
                onStatusUpdated = this@ModelManagerViewModel::setDownloadStatus,
              )
            }

            checkedModelNames.add(model.name)
          }
        }
      }
    }
  }

  fun loadModelAllowlist() {
    // relay: the allowlist load is owned by ModelRegistry so a headless start (BootReceiver) can
    // populate task.models with no Activity. Box's changes to Google's body -- bundled-assets
    // first, the `task.models.none { it.name == ... }` duplicate guards, and the
    // loadingModelAllowlist/error state updates -- moved into ModelRegistry.loadModelAllowlist
    // with it; this Activity-side wrapper only mirrors the result into the UI state.
    _uiState.update { it.copy(loadingModelAllowlist = true, loadingModelAllowlistError = "") }
    modelRegistry.loadModelAllowlist(
      onDone = {
        viewModelScope.launch(Dispatchers.IO) {
          val curTasks = getActiveCustomTasks().map { it.task }
          _uiState.update {
            createUiState()
              .copy(
                loadingModelAllowlist = false,
                tasks = curTasks,
                tasksByCategory = groupTasksByCategory(),
              )
          }
          processPendingDownloads()
          checkAICoreModelStatuses()
          Log.d(TAG, "loadModelAllowlist: Done")
        }
      },
      onError = { error ->
        _uiState.update {
          it.copy(loadingModelAllowlist = false, loadingModelAllowlistError = error)
        }
      },
    )
  }

  fun clearLoadModelAllowlistError() {
    viewModelScope.launch(Dispatchers.IO) {
      val curTasks = getActiveCustomTasks().map { it.task }
      processTasks()
      _uiState.update {
        createUiState()
          .copy(
            loadingModelAllowlist = false,
            tasks = curTasks,
            loadingModelAllowlistError = "",
            tasksByCategory = groupTasksByCategory(),
          )
      }
    }
  }

  fun setAppInForeground(foreground: Boolean) {
    lifecycleProvider.isAppInForeground = foreground
  }

  private fun saveModelAllowlistToDisk(modelAllowlistContent: String) {
    try {
      Log.d(TAG, "Saving model allowlist to disk...")
      val file = File(modelsDir, MODEL_ALLOWLIST_FILENAME)
      file.writeText(modelAllowlistContent)
      Log.d(TAG, "Done: saving model allowlist to disk.")
    } catch (e: Exception) {
      Log.e(TAG, "failed to write model allowlist to disk", e)
    }
  }

  private fun readModelAllowlistFromDisk(
    fileName: String = MODEL_ALLOWLIST_FILENAME
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

  private fun isModelPartiallyDownloaded(model: Model): Boolean {
    if (model.downloadInfo.localModelFilePathOverride.isNotEmpty()) {
      return false
    }

    // A model is partially downloaded when the tmp file exists.
    val tmpFilePath =
      model.getPath(
        context = context,
        fileName = "${model.downloadInfo.downloadFileName}.$TMP_FILE_EXT",
      )
    return File(tmpFilePath).exists()
  }

  private fun createEmptyUiState(): ModelManagerUiState {
    return ModelManagerUiState(
      tasks = listOf(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = mapOf(),
    )
  }

  private fun createUiState(): ModelManagerUiState {
    val modelDownloadStatus: MutableMap<String, ModelDownloadStatus> = mutableMapOf()
    val extraDataDownloadStatus = _uiState.value.extraDataDownloadStatus.toMutableMap()
    val tasks: MutableMap<String, Task> = mutableMapOf()
    val checkedModelNames = mutableSetOf<String>()
    val checkedModels = mutableListOf<Model>()
    for (customTask in getActiveCustomTasks()) {
      val task = customTask.task
      tasks.put(key = task.id, value = task)
      for (model in task.models.toList()) {
        if (checkedModelNames.contains(model.name)) {
          continue
        }
        modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
        checkedModelNames.add(model.name)
        checkedModels.add(model)
      }
    }

    for (model in checkedModels) {
      if (
        extraDataDownloadStatus[model.name] == null &&
          getModelFamily(model).any { isExtraDataPresentOnDisk(it) }
      ) {
        extraDataDownloadStatus[model.name] =
          ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
      }
    }

    // relay: ModelRegistry attaches imported models to their tasks idempotently, so a
    // BootReceiver-driven restore and this Activity-driven one cannot double-add. Google's own
    // attach code is superseded by it; only the download-status mapping stays here.
    modelRegistry.restoreImportedModels()
    // Box: imported Stable-Diffusion GGUFs are files on disk, not DataStore entries.
    val sdImportsDir = File(modelsDir, SD_IMPORTS_DIR)
    if (sdImportsDir.exists()) {
      for (file in sdImportsDir.listFiles { _, name -> name.endsWith(".gguf") } ?: emptyArray()) {
        modelDownloadStatus[file.name] =
          ModelDownloadStatus(
            status = ModelDownloadStatusType.SUCCEEDED,
            receivedBytes = file.length(),
            totalBytes = file.length(),
          )
      }
    }

    for (importedModel in dataStoreRepository.readImportedModels()) {
      val model = modelRegistry.getModelByName(importedModel.fileName) ?: continue
      if (model.downloadInfo.url.isNotEmpty()) {
        modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
      } else {
        modelDownloadStatus[model.name] =
          ModelDownloadStatus(
            status = ModelDownloadStatusType.SUCCEEDED,
            receivedBytes = importedModel.fileSize,
            totalBytes = importedModel.fileSize,
          )
      }
    }

    val textInputHistory = dataStoreRepository.readTextInputHistory()
    Log.d(TAG, "text input history: $textInputHistory")

    Log.d(TAG, "model download status: $modelDownloadStatus")
    return ModelManagerUiState(
      tasks = getActiveCustomTasks().map { it.task }.toList(),
      tasksByCategory = mapOf(),
      modelDownloadStatus = modelDownloadStatus,
      textInputHistory = textInputHistory,
      downloadOptionalComponents = _uiState.value.downloadOptionalComponents,
      extraDataDownloadStatus = extraDataDownloadStatus,
    )
  }

  private fun createModelFromImportedModelInfo(info: ImportedModel): Model =
    modelRegistry.createModelFromImportedModelInfo(info = info)

  private fun groupTasksByCategory(): Map<String, List<Task>> = modelRegistry.groupTasksByCategory()

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

  /**
   * Retrieves the download status of a model.
   *
   * This function determines the download status of a given model by checking if it's fully
   * downloaded, partially downloaded, or not downloaded at all. It also retrieves the received and
   * total bytes for partially downloaded models.
   */
  private fun getModelDownloadStatus(model: Model): ModelDownloadStatus =
    modelRegistry.getModelDownloadStatus(model)

  private fun isFileInModelsDir(fileName: String): Boolean =
    modelRegistry.isFileInModelsDir(fileName)

  private fun isFileInDataLocalTmpDir(fileName: String): Boolean =
    modelRegistry.isFileInDataLocalTmpDir(fileName)

  private fun deleteFileFromModelsDir(fileName: String) =
    modelRegistry.deleteFileFromModelsDir(fileName)

  private fun deleteFilesFromImportDir(fileName: String) =
    modelRegistry.deleteFilesFromImportDir(fileName)

  private fun deleteDirFromModelsDir(dir: String) = modelRegistry.deleteDirFromModelsDir(dir)

  fun isModelDownloaded(model: Model): Boolean = modelRegistry.isModelDownloaded(model)
}

private fun getAllowlistUrl(version: String): String {
  return "$ALLOWLIST_BASE_URL/${version}.json"
}
