// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task

class TaskCatalog(private val customTasks: Set<CustomTask>) {

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
      val bestModel = task.models.find { it.bestForTaskIds.contains(task.id) }
      if (bestModel != null) {
        task.models.remove(bestModel)
        task.models.add(0, bestModel)
      }
    }
  }

  internal fun addModelIfAbsent(task: Task?, model: Model) {
    if (task != null && task.models.none { it.name == model.name }) {
      task.models.add(model)
    }
  }
}
