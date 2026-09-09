// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.content.Context
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.relay.runtime.ModelEngine
import com.google.ai.edge.gallery.relay.runtime.engineFor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Exists for callers (OpenAiServer, boot paths) that must not depend on Activity-owned state.
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

  // Process-scoped so a boot-triggered load is not cancelled when an Activity's ViewModel is
  // cleared; never explicitly cancelled.
  private val registryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val taskCatalog = TaskCatalog(customTasks)
  private val modelFiles = ModelFiles(context, modelsDir)
  private val importedModelStore = ImportedModelStore(modelsDir, taskCatalog, dataStoreRepository)
  private val modelAllowlistLoader =
    ModelAllowlistLoader(context, modelsDir, registryScope, taskCatalog)
  private val modelLifecycle = ModelLifecycle(registryScope, taskCatalog, systemPromptRepository)

  fun getTaskById(id: String): Task? = taskCatalog.getTaskById(id)

  fun getTasksByIds(ids: Set<String>): List<Task> = taskCatalog.getTasksByIds(ids)

  fun getCustomTaskByTaskId(id: String): CustomTask? = taskCatalog.getCustomTaskByTaskId(id)

  fun getActiveCustomTasks(): List<CustomTask> = taskCatalog.getActiveCustomTasks()

  val tasks: List<Task>
    get() = taskCatalog.tasks

  fun getModelByName(name: String): Model? = taskCatalog.getModelByName(name)

  fun engineOf(model: Model): ModelEngine =
    model.engineFor(taskCatalog.getTaskForModel(model)?.id)

  fun getAllModels(): List<Model> = taskCatalog.getAllModels()

  fun processTasks() = taskCatalog.processTasks()

  fun recordEngineAccelerator(modelName: String, acceleratorLabel: String) =
    modelLifecycle.recordEngineAccelerator(modelName, acceleratorLabel)

  fun getEngineAccelerator(modelName: String): String? =
    modelLifecycle.getEngineAccelerator(modelName)

  fun acquireHold(modelName: String, holder: String) =
    modelLifecycle.acquireHold(modelName, holder)

  fun releaseHold(modelName: String, holder: String) =
    modelLifecycle.releaseHold(modelName, holder)

  fun holdersOf(modelName: String): Set<String> = modelLifecycle.holdersOf(modelName)

  fun heldModelNames(): Set<String> = modelLifecycle.heldModelNames()

  fun isFirstInitialization(model: Model): Boolean = modelLifecycle.isFirstInitialization(model)

  fun forgetInitializedBackends(modelName: String) =
    modelLifecycle.forgetInitializedBackends(modelName)

  fun restoreImportedModels() = importedModelStore.restoreImportedModels()

  fun createImportedSdModel(fileName: String, fileSize: Long, url: String = ""): Model =
    importedModelStore.createImportedSdModel(fileName, fileSize, url)

  internal fun createModelFromImportedModelInfo(info: ImportedModel): Model =
    importedModelStore.createModelFromImportedModelInfo(info)

  fun loadModelAllowlist(onDone: () -> Unit = {}, onError: (String) -> Unit = {}) =
    modelAllowlistLoader.loadModelAllowlist(onDone, onError)

  val allowlistModels: List<Model>
    get() = modelAllowlistLoader.allowlistModels

  fun groupTasksByCategory(): Map<String, List<Task>> = modelAllowlistLoader.groupTasksByCategory()

  fun getModelDownloadStatus(model: Model): ModelDownloadStatus =
    modelFiles.getModelDownloadStatus(model)

  fun isFileInModelsDir(fileName: String): Boolean = modelFiles.isFileInModelsDir(fileName)

  fun isFileInDataLocalTmpDir(fileName: String): Boolean =
    modelFiles.isFileInDataLocalTmpDir(fileName)

  fun deleteFileFromModelsDir(fileName: String) = modelFiles.deleteFileFromModelsDir(fileName)

  fun deleteFilesFromImportDir(fileName: String) = modelFiles.deleteFilesFromImportDir(fileName)

  fun deleteDirFromModelsDir(dir: String) = modelFiles.deleteDirFromModelsDir(dir)

  fun isModelDownloaded(model: Model): Boolean = modelFiles.isModelDownloaded(model)

  fun initializeModel(
    context: Context,
    task: Task,
    model: Model,
    force: Boolean = false,
    onDone: () -> Unit = {},
    onError: (String) -> Unit = {},
  ) = modelLifecycle.initializeModel(context, task, model, force, onDone, onError)

  suspend fun cleanupModelAwait(context: Context, task: Task, model: Model) =
    modelLifecycle.cleanupModelAwait(context, task, model)

  fun cleanupModel(
    context: Context,
    task: Task,
    model: Model,
    instanceToCleanUp: Any? = model.instance,
    onDone: () -> Unit = {},
  ) = modelLifecycle.cleanupModel(context, task, model, instanceToCleanUp, onDone)
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface ModelRegistryEntryPoint {
  fun modelRegistry(): ModelRegistry
}
