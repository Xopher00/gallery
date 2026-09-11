// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.util.Log
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadInfo
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.SD_IMPORTS_DIR
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.createLlmChatConfigs
import com.google.ai.edge.gallery.proto.ImportedModel
import java.io.File

internal val RESET_CONVERSATION_TURN_COUNT_CONFIG =
  NumberSliderConfig(
    key = ConfigKeys.RESET_CONVERSATION_TURN_COUNT,
    sliderMin = 1f,
    sliderMax = 30f,
    defaultValue = 3f,
    valueType = ValueType.INT,
  )

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

      taskCatalog.addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_CHAT }, model)
      taskCatalog.addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_PROMPT_LAB }, model)
      taskCatalog.addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_AGENT_CHAT }, model)
      if (model.llmSupportImage) {
        taskCatalog.addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_ASK_IMAGE }, model)
      }
      if (model.llmSupportAudio) {
        taskCatalog.addModelIfAbsent(curTasks.find { it.id == BuiltInTaskId.LLM_ASK_AUDIO }, model)
      }
      if (model.llmSupportTinyGarden) {
        taskCatalog.addModelIfAbsent(
          curTasks.find { it.id == BuiltInTaskId.LLM_TINY_GARDEN },
          model,
        )
        val newConfigs = model.configs.toMutableList()
        newConfigs.add(RESET_CONVERSATION_TURN_COUNT_CONFIG)
        model.configs = newConfigs
        model.preProcess()
      }
      if (model.llmSupportMobileActions) {
        taskCatalog.addModelIfAbsent(
          curTasks.find { it.id == BuiltInTaskId.LLM_MOBILE_ACTIONS },
          model,
        )
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
    val importedFileNameToCheck =
      if (info.fileName.startsWith("$IMPORTS_DIR/")) {
        info.fileName.substringAfter("$IMPORTS_DIR/")
      } else {
        info.fileName
      }
    val importedRuntimeType =
      if (importedFileNameToCheck.endsWith(".gguf", ignoreCase = true)) {
        RuntimeType.UNKNOWN
      } else {
        RuntimeType.LITERT_LM
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
        showRunAgainButton = false,
        llmSupportImage = llmSupportImage,
        llmSupportAudio = llmSupportAudio,
        llmSupportTinyGarden = llmSupportTinyGarden,
        llmSupportMobileActions = llmSupportMobileActions,
        capabilities = capabilities.toList(),
        capabilityToTaskTypes = capabilityToTaskTypes.toMap(),
        llmMaxToken = llmMaxToken,
        accelerators = accelerators,
        isLlm = true,
        runtimeType = importedRuntimeType,
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
