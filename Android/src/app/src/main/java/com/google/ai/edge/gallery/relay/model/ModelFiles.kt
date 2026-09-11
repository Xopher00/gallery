// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelFile
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import java.io.File

internal const val TAG = "AGModelRegistry"

class ModelFiles(private val context: Context, private val modelsDir: File) {

  fun getModelDownloadStatus(model: Model): ModelDownloadStatus =
    getModelDownloadStatus(context = context, modelsDir = modelsDir, model = model)

  fun isFileInModelsDir(fileName: String): Boolean = isFileInModelsDir(modelsDir, fileName)

  fun isFileInDataLocalTmpDir(fileName: String): Boolean {
    val file = File("/data/local/tmp", fileName)
    return file.exists()
  }

  fun deleteFileFromModelsDir(fileName: String) = deleteFileFromModelsDir(modelsDir, fileName)

  fun deleteFilesFromImportDir(fileName: String) = deleteFilesFromImportDir(modelsDir, fileName)

  fun deleteDirFromModelsDir(dir: String) = deleteDirFromModelsDir(modelsDir, dir)

  fun isModelDownloaded(model: Model): Boolean = isModelDownloaded(modelsDir, model)

  private fun isModelPartiallyDownloaded(context: Context, model: Model): Boolean {
    if (model.downloadInfo.localModelFilePathOverride.isNotEmpty()) {
      return false
    }

    val tmpFilePath =
      model.getPath(context = context, fileName = "${model.downloadInfo.downloadFileName}.$TMP_FILE_EXT")
    return File(tmpFilePath).exists()
  }

  private fun getModelDownloadStatus(
    context: Context,
    modelsDir: File,
    model: Model,
  ): ModelDownloadStatus {
    Log.d(TAG, "Checking model ${model.name} download status...")

    if (model.downloadInfo.localRelativeDirPathOverride.isNotEmpty()) {
      Log.d(TAG, "Model has localRelativeDirPathOverride set. Set status to SUCCEEDED")
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.SUCCEEDED,
        receivedBytes = 0,
        totalBytes = 0,
      )
    }

    var status = ModelDownloadStatusType.NOT_DOWNLOADED
    var receivedBytes = 0L
    var totalBytes = 0L
    var isUpdatable = false
    var installedModelFile: ModelFile? = null

    if (isModelPartiallyDownloaded(context = context, model = model)) {
      status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED
      val tmpFilePath =
        model.getPath(context = context, fileName = "${model.downloadInfo.downloadFileName}.$TMP_FILE_EXT")
      val tmpFile = File(tmpFilePath)
      receivedBytes = tmpFile.length()
      totalBytes = model.downloadInfo.totalBytes
      Log.d(TAG, "${model.name} is partially downloaded. $receivedBytes/$totalBytes")
    } else if (checkIfModelDownloaded(modelsDir, model, model.downloadInfo.version)) {
      status = ModelDownloadStatusType.SUCCEEDED
      installedModelFile =
        ModelFile(
          fileName = model.downloadInfo.downloadFileName,
          commitHash = model.downloadInfo.version,
        )
      Log.d(TAG, "${model.name} has been downloaded.")
    } else {
      // Not on the latest version -- an older, updatable one may still be on disk.
      val updatableMatch =
        model.downloadInfo.updatableModelFiles.firstOrNull { updatableFile ->
          updatableFile.commitHash.isNotEmpty() &&
            checkIfModelDownloaded(modelsDir, model, updatableFile.commitHash, updatableFile.fileName)
        }
      if (updatableMatch != null) {
        status = ModelDownloadStatusType.SUCCEEDED
        isUpdatable = true
        installedModelFile = updatableMatch
        Log.d(TAG, "${model.name} has been downloaded (updatable from ${updatableMatch.commitHash}).")
      } else {
        Log.d(TAG, "${model.name} has not been downloaded.")
      }
    }

    return ModelDownloadStatus(
      status = status,
      receivedBytes = receivedBytes,
      totalBytes = totalBytes,
      isUpdatable = isUpdatable,
      installedModelFile = installedModelFile,
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
    fileName: String = model.downloadInfo.downloadFileName,
  ): Boolean {
    val modelRelativePath =
      if (model.downloadInfo.imported) {
        listOf(IMPORTS_DIR, fileName).joinToString(File.separator)
      } else {
        listOf(model.normalizedName, version, fileName).joinToString(File.separator)
      }
    val downloadedFileExists =
      fileName.isNotEmpty() &&
        ((model.downloadInfo.localModelFilePathOverride.isEmpty() &&
          isFileInModelsDir(modelsDir, modelRelativePath)) ||
          (model.downloadInfo.localModelFilePathOverride.isNotEmpty() &&
            File(model.downloadInfo.localModelFilePathOverride).exists()))

    val unzippedDirectoryExists =
      model.downloadInfo.isZip &&
        model.downloadInfo.unzipDir.isNotEmpty() &&
        isFileInModelsDir(
          modelsDir,
          listOf(model.normalizedName, version, model.downloadInfo.unzipDir).joinToString(File.separator),
        )

    return downloadedFileExists || unzippedDirectoryExists
  }

  // Model is immutable, so this can no longer signal "which version matched" via mutation --
  // that finer detail is getModelDownloadStatus's job.
  private fun isModelDownloaded(modelsDir: File, model: Model): Boolean {
    if (checkIfModelDownloaded(modelsDir, model, model.downloadInfo.version)) return true

    return model.downloadInfo.updatableModelFiles.any { updatableFile ->
      updatableFile.commitHash.isNotEmpty() &&
        checkIfModelDownloaded(modelsDir, model, updatableFile.commitHash, updatableFile.fileName)
    }
  }
}
