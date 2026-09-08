package com.google.ai.edge.gallery.ui.imagegen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SD_IMPORTS_DIR
import com.google.ai.edge.gallery.stablediffusion.StableDiffusion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class ImageSize(val width: Int, val height: Int, val label: String)

val IMAGE_SIZE_PRESETS = listOf(
    ImageSize(256, 256, "256²"),
    ImageSize(512, 512, "512²"),
    ImageSize(512, 768, "512×768"),
    ImageSize(768, 512, "768×512"),
    ImageSize(768, 768, "768²"),
)

data class ImageGenUiState(
    val prompt: String = "",
    val negativePrompt: String = "ugly, deformed, blurry, low quality",
    val steps: Int = 20,
    val cfgScale: Float = 7.5f,
    val selectedSize: ImageSize = IMAGE_SIZE_PRESETS[1],
    val isGenerating: Boolean = false,
    val progressStep: Int = 0,
    val progressTotal: Int = 0,
    val generatedBitmap: Bitmap? = null,
    val errorMessage: String? = null,
    val isImporting: Boolean = false,
    val importProgress: Float = 0f,
    val imageSaved: Boolean = false,
)

@HiltViewModel
class ImageGenViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ImageGenUiState())
    val uiState: StateFlow<ImageGenUiState> = _uiState.asStateFlow()

    fun updatePrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(prompt = prompt, errorMessage = null)
    }

    fun updateNegativePrompt(negativePrompt: String) {
        _uiState.value = _uiState.value.copy(negativePrompt = negativePrompt)
    }

    fun updateSteps(steps: Int) {
        _uiState.value = _uiState.value.copy(steps = steps)
    }

    fun updateCfgScale(cfgScale: Float) {
        _uiState.value = _uiState.value.copy(cfgScale = cfgScale)
    }

    fun updateSize(size: ImageSize) {
        _uiState.value = _uiState.value.copy(selectedSize = size)
    }

    fun saveImage(context: Context) {
        val bitmap = _uiState.value.generatedBitmap ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = "local_diffusion_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LocalDiffusion")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                _uiState.value = _uiState.value.copy(imageSaved = true)
            }
        }
    }

    fun clearSavedFlag() {
        _uiState.value = _uiState.value.copy(imageSaved = false)
    }

    fun importSdModel(
        context: Context,
        uri: Uri,
        onDone: (fileName: String, fileSize: Long) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isImporting = true, importProgress = 0f)

            val (fileSize, fileName) = getFileInfo(context, uri)
            if (fileName.isEmpty()) {
                _uiState.value = _uiState.value.copy(isImporting = false, errorMessage = "Could not read file info")
                return@launch
            }

            val importsDir = File(context.getExternalFilesDir(null), SD_IMPORTS_DIR)
            importsDir.mkdirs()
            val outFile = File(importsDir, fileName)

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        var lastReport = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            written += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 200 && fileSize > 0) {
                                lastReport = now
                                _uiState.value = _uiState.value.copy(importProgress = written.toFloat() / fileSize)
                            }
                        }
                    }
                }
                _uiState.value = _uiState.value.copy(isImporting = false, importProgress = 1f)
                withContext(Dispatchers.Main) { onDone(fileName, outFile.length()) }
            } catch (e: Exception) {
                outFile.delete()
                _uiState.value = _uiState.value.copy(isImporting = false, errorMessage = "Import failed: ${e.message}")
            }
        }
    }

    private fun getFileInfo(context: Context, uri: Uri): Pair<Long, String> {
        var fileSize = 0L
        var fileName = ""
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                    fileName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
        return Pair(fileSize, fileName)
    }

    fun generateImage(model: Model) {
        val sd = model.instance as? StableDiffusion ?: run {
            _uiState.value = _uiState.value.copy(errorMessage = "Model not loaded")
            return
        }

        val state = _uiState.value
        if (state.prompt.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Enter a prompt first")
            return
        }

        val params = StableDiffusion.GenerationParams(
            prompt = state.prompt,
            negativePrompt = state.negativePrompt,
            width = state.selectedSize.width,
            height = state.selectedSize.height,
            steps = state.steps,
            cfgScale = state.cfgScale,
            seed = -1L,
        )

        _uiState.value = state.copy(
            isGenerating = true,
            progressStep = 0,
            progressTotal = state.steps,
            errorMessage = null,
        )

        viewModelScope.launch {
            sd.generateImage(params)
                .catch { e ->
                    _uiState.value = _uiState.value.copy(
                        isGenerating = false,
                        errorMessage = e.message ?: "Generation failed",
                    )
                }
                .collect { progress ->
                    if (progress.bitmap != null) {
                        _uiState.value = _uiState.value.copy(
                            isGenerating = false,
                            progressStep = progress.totalSteps,
                            progressTotal = progress.totalSteps,
                            generatedBitmap = progress.bitmap,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            progressStep = progress.step,
                            progressTotal = progress.totalSteps,
                        )
                    }
                }
        }
    }
}
