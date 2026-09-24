// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.util.Log
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.ModelUtils
import com.google.ai.edge.gallery.data.getTargetTaskIdsForImportedModel
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BackendSpec
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.LabelConfig
import com.google.ai.edge.gallery.data.LlmProfile
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadInfo
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SD_IMPORTS_DIR
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.proto.ImportedModel
import com.jegly.offlineLLM.smollm.GGUFReader
import java.io.File
import kotlin.math.ceil

internal val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )

fun estimatedMinDeviceMemoryInGb(sizeInBytes: Long): Int {
  val sizeInGb = sizeInBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
  return maxOf(4, ceil(sizeInGb * 3).toInt())
}

class ImportedModelStore(
  private val modelsDir: File,
  private val taskCatalog: TaskCatalog,
  private val dataStoreRepository: DataStoreRepository,
  private val cardDescriptionStore: HfCardDescriptionStore,
  private val queueCardDescription: (String) -> Unit = {},
) {

  fun restoreImportedModels() {
    val curTasks = taskCatalog.getActiveCustomTasks().map { it.task }
    dropStoredCardText()

    for (importedModel in dataStoreRepository.readImportedModels()) {
      Log.d(TAG, "stored imported model: $importedModel")
      val model = createModelFromImportedModelInfo(info = importedModel)
      // Backfills models imported before descriptions existed. Safe here because it is a metadata
      // fetch, never a model load.
      queueDescriptionFor(importedModel)

      for (taskId in model.getTargetTaskIdsForImportedModel()) {
        taskCatalog.addModelIfAbsent(curTasks.find { it.id == taskId }, model)
      }
    }

    val sdImportsDir = File(modelsDir, SD_IMPORTS_DIR)
    if (sdImportsDir.exists()) {
      val imageGenTask = curTasks.find { it.id == BuiltInTaskId.IMAGE_GEN }
      for (file in sdImportsDir.listFiles { _, name -> name.endsWith(".gguf") } ?: emptyArray()) {
        val model = createImportedSdModel(fileName = file.name, fileSize = file.length())
        taskCatalog.addModelIfAbsent(imageGenTask, model)
      }
    }
  }

  // Shared with restoreImportedModels via taskCatalog/dataStoreRepository, not a second copy.
  fun addImportedLlmModel(info: ImportedModel): Model {
    Log.d(TAG, "adding imported llm model: $info")

    val importsDir = File(modelsDir, IMPORTS_DIR)
    if (!importsDir.exists()) {
      importsDir.mkdirs()
    }

    val model = createModelFromImportedModelInfo(info = info)
    queueDescriptionFor(info)

    for (task in taskCatalog.getActiveCustomTasks().map { it.task }) {
      val modelIndex =
        task.models.indexOfFirst { info.fileName == it.name && it.downloadInfo.imported }
      if (modelIndex >= 0) {
        Log.d(TAG, "duplicated imported model found in task. Removing it first")
        task.models.removeAt(modelIndex)
        task.updateTrigger.value = System.currentTimeMillis()
      }
    }
    for (task in taskCatalog.getTasksByIds(ids = model.getTargetTaskIdsForImportedModel())) {
      task.models.add(model)
      task.updateTrigger.value = System.currentTimeMillis()
    }

    val importedModels = dataStoreRepository.readImportedModels().toMutableList()
    val importedModelIndex = importedModels.indexOfFirst { info.fileName == it.fileName }
    if (importedModelIndex >= 0) {
      Log.d(TAG, "duplicated imported model found in data store. Removing it first")
      importedModels.removeAt(importedModelIndex)
    }
    importedModels.add(info)
    dataStoreRepository.saveImportedModels(importedModels = importedModels)

    return model
  }

  fun createImportedSdModel(fileName: String, fileSize: Long, url: String = ""): Model =
    Model(
        name = fileName,
        info = "Imported SD GGUF model",
        downloadInfo =
          ModelDownloadInfo(
            url = url,
            sizeInBytes = fileSize,
            downloadFileName = "$SD_IMPORTS_DIR${File.separator}$fileName",
            imported = true,
          ),
        minDeviceMemoryInGb = estimatedMinDeviceMemoryInGb(fileSize),
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
      )
      .also { it.preProcess() }

  internal fun createModelFromImportedModelInfo(info: ImportedModel): Model {
    val accelerators: MutableList<Accelerator> =
      info.llmConfig.compatibleAcceleratorsList
        .mapNotNull { acceleratorLabel ->
          when (acceleratorLabel.trim()) {
            Accelerator.GPU.label -> Accelerator.GPU
            Accelerator.CPU.label -> Accelerator.CPU
            Accelerator.NPU.label -> Accelerator.NPU

            else -> null
          }
        }
        .ifEmpty { listOf(Accelerator.CPU) }
        .toMutableList()
    val llmMaxToken = info.llmConfig.defaultMaxTokens
    val llmSupportImage = info.llmConfig.supportImage
    val llmSupportAudio = info.llmConfig.supportAudio
    val llmSupportTinyGarden = info.llmConfig.supportTinyGarden
    val llmSupportMobileActions = info.llmConfig.supportMobileActions
    val llmSupportThinking = info.llmConfig.supportThinking
    val llmSupportSpeculativeDecoding = info.llmConfig.supportSpeculativeDecoding
    val isForTestOnly = ModelUtils.isImportedUrlForTestOnly(info.url)
    val importedFileNameToCheck =
      if (info.fileName.startsWith("$IMPORTS_DIR/")) {
        info.fileName.substringAfter("$IMPORTS_DIR/")
      } else {
        info.fileName
      }
    val importedFilePath = importedFile(modelsDir, importedFileNameToCheck).absolutePath
    val isGgufImport = importedFileNameToCheck.endsWith(".gguf", ignoreCase = true)
    var ggufReadFailed = false
    val ggufDeclaredContextLength =
      if (isGgufImport) {
        try {
          GGUFReader().use { it.open(importedFilePath); it.getContextSize() }
        } catch (e: Exception) {
          ggufReadFailed = true
          null
        }
      } else {
        null
      }
    val modelFileDetails =
      resolveModelFileDetails(
        fileName = importedFileNameToCheck,
        litertlmHeaderPath = importedFilePath,
        ggufDeclaredContextLength = ggufDeclaredContextLength,
      )
    val importedMaxContextLength =
      if (isGgufImport) {
        if (ggufReadFailed) {
          4096
        } else {
          val engineContextLimit =
            if (File(importedFilePath).length() > 2_147_483_648L) 4096 else 8192
          ggufDeclaredContextLength?.let { minOf(it, engineContextLimit.toLong()).toInt() }
            ?: engineContextLimit
        }
      } else {
        modelFileDetails.contextLength?.value ?: 4096
      }
    val configs: MutableList<Config> =
      createLlmChatConfigs(
          defaultMaxToken = llmMaxToken,
          defaultMaxContextLength = importedMaxContextLength,
          defaultTopK = info.llmConfig.defaultTopk,
          defaultTopP = info.llmConfig.defaultTopp,
          defaultTemperature = info.llmConfig.defaultTemperature,
          accelerators = accelerators,
          supportThinking = llmSupportThinking,
          supportSpeculativeDecoding = llmSupportSpeculativeDecoding,
        )
        .toMutableList()
    buildModelFileDetailsText(details = modelFileDetails, selectedAccelerators = accelerators)?.let {
      configs.add(1, LabelConfig(key = MODEL_FILE_DETAILS_LABEL_KEY, defaultValue = it))
    }
    if (llmSupportTinyGarden && !isForTestOnly) {
      configs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
    }
    val capabilityToTaskTypes =
      ModelUtils.buildImportedModelCapabilityToTaskTypes(
        supportThinking = llmSupportThinking,
        supportSpeculativeDecoding = llmSupportSpeculativeDecoding,
        isForTestOnly = isForTestOnly,
      )
    val capabilities = capabilityToTaskTypes.keys.toMutableList()
    val importedRuntimeType =
      if (importedFileNameToCheck.endsWith(".gguf", ignoreCase = true)) {
        RuntimeType.UNKNOWN
      } else {
        RuntimeType.LITERT_LM
      }
    val isEmbeddingModel = probeModelFileKind(importedFilePath) == ModelFileKind.EMBEDDING
    if (isEmbeddingModel) {
      capabilities.add(ModelCapability.EMBEDDING)
    }
    val hfModelId = hfModelIdFromUrl(info.url)
    val downloadInfo =
      ModelDownloadInfo(
        url = info.url,
        sizeInBytes = info.fileSize,
        downloadFileName = info.fileName,
        imported = true,
      )
    val model =
      Model(
        // name stays the file name: it is the identity key for downloads, mutexes and the API.
        name = info.fileName,
        displayName = importedDisplayName(fileName = info.fileName, hfModelId = hfModelId),
        // Lookup only. The fetch is triggered by queueDescriptionFor, on import not on restore.
        info =
          if (hfModelId.isEmpty()) "" else cardDescriptionStore.get(hfModelId)?.description.orEmpty(),
        configs = configs,
        downloadInfo = downloadInfo,
        minDeviceMemoryInGb = estimatedMinDeviceMemoryInGb(info.fileSize),
        showRunAgainButton = false,
        supportImage = llmSupportImage,
        supportAudio = llmSupportAudio,
        // LlmProfile.init{} throws on a non-positive maxTokens; a proto default of 0 must not reach
        // it. null for an embedding model keeps isLlm (computed from llmProfile) false.
        llmProfile =
          if (isEmbeddingModel) {
            null
          } else {
            LlmProfile(
              maxTokens = llmMaxToken.takeIf { it > 0 } ?: DEFAULT_MAX_TOKEN,
              supportTinyGarden = llmSupportTinyGarden,
              supportMobileActions = llmSupportMobileActions,
            )
          },
        capabilities = capabilities.toList(),
        capabilityToTaskTypes = capabilityToTaskTypes,
        backendSpec = BackendSpec(runtimeType = importedRuntimeType, accelerators = accelerators),
      )
    model.preProcess()

    return model
  }

  fun queueDescriptionFor(info: ImportedModel) {
    val hfModelId = hfModelIdFromUrl(info.url)
    if (hfModelId.isNotEmpty() && cardDescriptionStore.get(hfModelId) == null) {
      queueCardDescription(hfModelId)
    }
  }

  // Named after cardData.base_model, the upstream model the repo was derived from. That is
  // published data, so packaging suffixes never appear and the casing is the original author's.
  private fun importedDisplayName(fileName: String, hfModelId: String): String {
    val stem = fileName.substringAfterLast('/').substringBeforeLast('.')
    val baseModel = hfModelId.takeIf { it.isNotEmpty() }?.let { cardDescriptionStore.get(it) }
      ?.baseModel?.substringAfterLast('/')
    if (baseModel.isNullOrEmpty()) return stem

    // Whatever the file name adds beyond the base model name is the variant, with no vocabulary
    // of quantization tokens to keep up to date.
    val variant =
      stem.takeIf { it.startsWith(baseModel, ignoreCase = true) }
        ?.drop(baseModel.length)
        ?.trim('-', '_', '.', ' ')
    return if (variant.isNullOrEmpty()) baseModel else "$baseModel ($variant)"
  }

  // Empty for a local-file import, which correctly yields no description.
  private fun hfModelIdFromUrl(url: String): String =
    url.substringAfter("huggingface.co/", "").substringBefore("/resolve/")

  // One-time cleanup of the raw README blobs a previous version persisted; nothing reads them now.
  private fun dropStoredCardText() {
    val stored = dataStoreRepository.readImportedModels()
    if (stored.none { it.modelCardText.isNotEmpty() }) return
    dataStoreRepository.saveImportedModels(
      importedModels = stored.map { it.toBuilder().clearModelCardText().build() }
    )
  }
}
