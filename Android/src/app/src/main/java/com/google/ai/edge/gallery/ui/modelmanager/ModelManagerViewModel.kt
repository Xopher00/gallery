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
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.SD_IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAccessibility
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
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
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
import com.google.ai.edge.gallery.modelmanager.ModelRegistry
import com.google.ai.edge.gallery.openai.OpenAiServerState
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
  private val modelRegistry: ModelRegistry,
  val huggingFaceApiClient: HuggingFaceApiClient,
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

  val allowlistModels: List<Model>
    get() = modelRegistry.allowlistModels

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

  fun getTaskById(id: String): Task? = modelRegistry.getTaskById(id)

  fun getTasksByIds(ids: Set<String>): List<Task> = modelRegistry.getTasksByIds(ids)

  fun getCustomTaskByTaskId(id: String): CustomTask? = modelRegistry.getCustomTaskByTaskId(id)

  fun getActiveCustomTasks(): List<CustomTask> = modelRegistry.getActiveCustomTasks()

  fun getSelectedModel(): Model? {
    return uiState.value.selectedModel
  }

  open fun getModelByName(name: String): Model? = modelRegistry.getModelByName(name)

  fun getAllModels(): List<Model> = modelRegistry.getAllModels()

  open fun getAllDownloadedModels(): List<Model> {
    return getAllModels().filter {
      uiState.value.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED &&
        it.isLlm
    }
  }

  fun processTasks() = modelRegistry.processTasks()

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
  ) {
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
                receivedBytes = model.sizeInBytes,
                totalBytes = model.sizeInBytes,
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
    deleteModel(model = model, removeImportedFromModelList = false)

    // Start to send download request.
    downloadRepository.downloadModel(
      task = task,
      model = model,
      includeExtraDataFiles = includeExtraDataFiles,
      onStatusUpdated = this::setDownloadStatus,
    )
  }

  fun cancelDownloadModel(model: Model) {
    // AICore models cannot be deleted from the download repository within the app.
    if (model.runtimeType == RuntimeType.AICORE) {
      return
    }
    downloadRepository.cancelDownloadModel(model)
    deleteModel(model = model, removeImportedFromModelList = false)
  }

  fun deleteModel(model: Model, removeImportedFromModelList: Boolean = true) {
    OpenAiServerState.unpin(model.name)
    if (model.instance != null) {
      uiState.value.tasks
        .find { it.models.contains(model) }
        ?.let { cleanupModel(context = context, task = it, model = model) }
    }

    // If the currently downloaded model is an updatable version, reset the model to its latest
    // version and mark it as not updatable upon deletion.
    if (model.updatable) {
      model.updatable = false
      model.latestModelFile?.let {
        model.version = it.commitHash
        model.downloadFileName = it.fileName
      }
    }

    for (curTask in uiState.value.tasks) {
      if (curTask.models.any { it.name == model.name }) {
        val customTask = getCustomTaskByTaskId(id = curTask.id)
        customTask?.onDeleteModelFn(context = context, model = model)
      }
    }

    if (model.imported) {
      deleteFilesFromImportDir(model.downloadFileName)
    } else {
      deleteDirFromModelsDir(model.normalizedName)
    }

    // Update model download status to NotDownloaded.
    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[model.name] =
      ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
    modelRegistry.forgetInitializedBackends(model.name)

    // Delete model from the list if model is imported as a local model and
    // removeImportedFromModelList is
    // true.
    if (model.imported && removeImportedFromModelList) {
      for (curTask in uiState.value.tasks) {
        val index = curTask.models.indexOf(model)
        if (index >= 0) {
          curTask.models.removeAt(index)
        }
        curTask.updateTrigger.value = System.currentTimeMillis()
      }
      curModelDownloadStatus.remove(model.name)

      // Update data store.
      val importedModels = dataStoreRepository.readImportedModels().toMutableList()
      val importedModelIndex = importedModels.indexOfFirst { it.fileName == model.name }
      if (importedModelIndex >= 0) {
        importedModels.removeAt(importedModelIndex)
      }
      dataStoreRepository.saveImportedModels(importedModels = importedModels)
    }
    val updatedDownloadOptionalComponents = _uiState.value.downloadOptionalComponents.toMutableMap()
    updatedDownloadOptionalComponents.remove(model.name)
    _uiState.update {
      it.copy(
        modelDownloadStatus = curModelDownloadStatus,
        downloadOptionalComponents = updatedDownloadOptionalComponents,
        tasks = it.tasks.toList(),
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
    modelRegistry.cleanupModel(
      context = context,
      task = task,
      model = model,
      instanceToCleanUp = instanceToCleanUp,
      onDone = onDone,
    )
  }

  fun setDownloadStatus(curModel: Model, status: ModelDownloadStatus) {
    // Update model download progress.
    val curModelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    curModelDownloadStatus[curModel.name] = status
    // Delete downloaded file if status is failed or not_downloaded.
    if (
      status.status == ModelDownloadStatusType.FAILED ||
        status.status == ModelDownloadStatusType.NOT_DOWNLOADED
    ) {
      deleteFileFromModelsDir(curModel.downloadFileName)
    }

    _uiState.update { it.copy(modelDownloadStatus = curModelDownloadStatus) }
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
   * Retrieves whether Firebase Analytics collection is currently enabled.
   *
   * @return `true` if enabled or not explicitly disabled in settings; `false` if disabled.
   */
  fun readFirebaseAnalytics(): Boolean {
    return dataStoreRepository.readFirebaseAnalytics()
  }

  /**
   * Updates the user preference for Firebase Analytics data collection right away.
   *
   * Persists the setting to on-disk DataStore
   * (`dataStoreRepository.saveFirebaseAnalytics(enabled)`) and dynamically updates the live
   * Firebase SDK state via `firebaseAnalytics?.setAnalyticsCollectionEnabled(enabled)`.
   *
   * @param enabled `true` to enable diagnostic/analytics gathering; `false` to disable.
   */
  fun saveFirebaseAnalytics(enabled: Boolean) {
    dataStoreRepository.saveFirebaseAnalytics(enabled = enabled)
    firebaseAnalytics?.setAnalyticsCollectionEnabled(enabled)
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
      if (model.url.isEmpty()) {
        return@withContext ModelAccessibility.ACCESSIBLE
      }
      // If it's a Hugging Face URL, delegate to HuggingFaceApiClient.
      if (HuggingFaceApiClient.isHuggingFaceUrl(model.url)) {
        return@withContext huggingFaceApiClient.checkModelAccessibility(
          modelUrl = model.url,
          accessToken = accessToken,
        )
      }

      val responseCode: Int
      try {
        val url = URL(model.url)
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

  fun addImportedLlmModel(info: ImportedModel) {
    Log.d(TAG, "adding imported llm model: $info")

    val importsDir = File(modelsDir, IMPORTS_DIR)
    if (!importsDir.exists()) {
      importsDir.mkdirs()
    }

    // Create model.
    val model = createModelFromImportedModelInfo(info = info)

    val setOfTasks =
      mutableSetOf(
        BuiltInTaskId.LLM_CHAT,
        BuiltInTaskId.LLM_ASK_IMAGE,
        BuiltInTaskId.LLM_ASK_AUDIO,
        BuiltInTaskId.LLM_PROMPT_LAB,
        BuiltInTaskId.LLM_MOBILE_ACTIONS,
        BuiltInTaskId.LLM_AGENT_CHAT,
      )
    for (task in getTasksByIds(ids = setOfTasks)) {
      // Remove duplicated imported model if existed.
      val modelIndex = task.models.indexOfFirst { info.fileName == it.name && it.imported }
      if (modelIndex >= 0) {
        Log.d(TAG, "duplicated imported model found in task. Removing it first")
        task.models.removeAt(modelIndex)
      }
      if (
        (task.id == BuiltInTaskId.LLM_ASK_IMAGE && model.llmSupportImage) ||
          (task.id == BuiltInTaskId.LLM_ASK_AUDIO && model.llmSupportAudio) ||
          (task.id == BuiltInTaskId.LLM_MOBILE_ACTIONS && model.llmSupportMobileActions) ||
          (task.id != BuiltInTaskId.LLM_ASK_IMAGE &&
            task.id != BuiltInTaskId.LLM_ASK_AUDIO &&
            task.id != BuiltInTaskId.LLM_MOBILE_ACTIONS)
      ) {
        task.models.add(model)
        model.preProcess()
      }
      task.updateTrigger.value = System.currentTimeMillis()
    }

    // Add initial status and states.
    val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    if (model.url.isNotEmpty()) {
      modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
    } else {
      modelDownloadStatus[model.name] =
        ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = info.fileSize,
          totalBytes = info.fileSize,
        )
    }

    // Update ui state.
    _uiState.update {
      it.copy(
        tasks = it.tasks.toList(),
        modelDownloadStatus = modelDownloadStatus,
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }

    // Add to data store.
    val importedModels = dataStoreRepository.readImportedModels().toMutableList()
    val importedModelIndex = importedModels.indexOfFirst { info.fileName == it.fileName }
    if (importedModelIndex >= 0) {
      Log.d(TAG, "duplicated imported model found in data store. Removing it first")
      importedModels.removeAt(importedModelIndex)
    }
    importedModels.add(info)
    dataStoreRepository.saveImportedModels(importedModels = importedModels)
  }

  fun addImportedSdModel(fileName: String, fileSize: Long) {
    val model = createImportedSdModel(fileName = fileName, fileSize = fileSize)

    val task = getTasksByIds(ids = setOf(BuiltInTaskId.IMAGE_GEN)).firstOrNull() ?: return
    val existingIndex = task.models.indexOfFirst { it.name == model.name && it.imported }
    if (existingIndex >= 0) task.models.removeAt(existingIndex)
    task.models.add(model)
    model.preProcess()
    task.updateTrigger.value = System.currentTimeMillis()

    val modelDownloadStatus = uiState.value.modelDownloadStatus.toMutableMap()
    modelDownloadStatus[model.name] = ModelDownloadStatus(
      status = ModelDownloadStatusType.SUCCEEDED,
      receivedBytes = fileSize,
      totalBytes = fileSize,
    )
    // Model initialization state now lives on the Model itself (model.initStatusFlow), which
    // defaults to InitializationStatus.Idle at construction — no separate map entry needed.

    _uiState.update {
      uiState.value.copy(
        tasks = uiState.value.tasks.toList(),
        modelDownloadStatus = modelDownloadStatus,
        modelImportingUpdateTrigger = System.currentTimeMillis(),
      )
    }
  }

  private fun createImportedSdModel(fileName: String, fileSize: Long): Model =
    modelRegistry.createImportedSdModel(fileName = fileName, fileSize = fileSize)

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
                model.accessToken = tokenStatusAndData.data.accessToken
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
    _uiState.update { it.copy(loadingModelAllowlist = true, loadingModelAllowlistError = "") }

    modelRegistry.loadModelAllowlist(
      onDone = {
        viewModelScope.launch(Dispatchers.IO) {
          val curTasks = getActiveCustomTasks().map { it.task }
          Log.d(TAG, "loadModelAllowlist: Updating UI state")
          _uiState.update {
            createUiState()
              .copy(
                loadingModelAllowlist = false,
                tasks = curTasks,
                tasksByCategory = groupTasksByCategory(),
              )
          }
          Log.d(TAG, "loadModelAllowlist: Processing pending downloads")
          processPendingDownloads()
          Log.d(TAG, "loadModelAllowlist: Checking AICore model statuses")
          checkAICoreModelStatuses()
          Log.d(TAG, "loadModelAllowlist: Done")
        }
      },
      onError = { error ->
        _uiState.update { it.copy(loadingModelAllowlist = false, loadingModelAllowlistError = error) }
      },
    )
  }

  fun clearLoadModelAllowlistError() {
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

  private fun readModelAllowlistFromAssets(): ModelAllowlist? {
    try {
      Log.d(TAG, "Reading model allowlist from assets...")
      val content = context.assets.open(MODEL_ALLOWLIST_FILENAME).bufferedReader().use { it.readText() }
      Log.d(TAG, "Model allowlist content from assets: $content")
      val gson = Gson()
      return gson.fromJson(content, ModelAllowlist::class.java)
    } catch (e: Exception) {
      Log.e(TAG, "failed to read model allowlist from assets", e)
      return null
    }
  }

  private fun isModelPartiallyDownloaded(model: Model): Boolean {
    if (model.localModelFilePathOverride.isNotEmpty()) {
      return false
    }

    // A model is partially downloaded when the tmp file exists.
    val tmpFilePath =
      model.getPath(context = context, fileName = "${model.downloadFileName}.$TMP_FILE_EXT")
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
    val checkedModelNames = mutableSetOf<String>()
    for (customTask in getActiveCustomTasks()) {
      val task = customTask.task
      for (model in task.models) {
        if (checkedModelNames.contains(model.name)) {
          continue
        }
        modelDownloadStatus[model.name] = getModelDownloadStatus(model = model)
        checkedModelNames.add(model.name)
      }
    }

    modelRegistry.restoreImportedModels()

    for (importedModel in dataStoreRepository.readImportedModels()) {
      val model = getModelByName(importedModel.fileName) ?: continue
      if (model.url.isNotEmpty()) {
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

    val sdImportsDir = File(modelsDir, SD_IMPORTS_DIR)
    if (sdImportsDir.exists()) {
      for (file in sdImportsDir.listFiles { _, name -> name.endsWith(".gguf") } ?: emptyArray()) {
        modelDownloadStatus[file.name] = ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = file.length(),
          totalBytes = file.length(),
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
    )
  }

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
        listOf(
          BuiltInTaskId.LLM_CHAT,
          BuiltInTaskId.LLM_ASK_IMAGE,
          BuiltInTaskId.LLM_ASK_AUDIO,
        )
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
        runtimeType = RuntimeType.LITERT_LM,
      )
    model.preProcess()

    return model
  }

  private fun groupTasksByCategory(): Map<String, List<Task>> = modelRegistry.groupTasksByCategory()

  private fun getModelDownloadStatus(model: Model): ModelDownloadStatus =
    modelRegistry.getModelDownloadStatus(model)

  private fun isFileInDataLocalTmpDir(fileName: String): Boolean {
    val file = File("/data/local/tmp", fileName)
    return file.exists()
  }

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
