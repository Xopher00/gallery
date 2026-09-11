// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.ui.imagegen

import com.google.ai.edge.gallery.R
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadInfo
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.stablediffusion.StableDiffusion
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val SD_STEPS_CONFIG = NumberSliderConfig(
    key = ConfigKey("sd_steps", "Steps", R.string.config_label_sd_steps),
    sliderMin = 1f,
    sliderMax = 50f,
    defaultValue = 20f,
    valueType = ValueType.INT,
    needReinitialization = false,
)
private val SD_CFG_CONFIG = NumberSliderConfig(
    key = ConfigKey("sd_cfg", "CFG Scale", R.string.config_label_sd_cfg),
    sliderMin = 1f,
    sliderMax = 20f,
    defaultValue = 7.5f,
    valueType = ValueType.FLOAT,
    needReinitialization = false,
)

private fun sdModel(
    name: String,
    modelId: String,
    modelFile: String,
    commitHash: String,
    description: String,
    sizeInBytes: Long,
): Model = Model(
    name = name,
    info = description,
    downloadInfo =
        ModelDownloadInfo(
            url = "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true",
            sizeInBytes = sizeInBytes,
            downloadFileName = modelFile,
            version = commitHash,
        ),
    configs = mutableListOf(SD_STEPS_CONFIG, SD_CFG_CONFIG),
    learnMoreUrl = "https://huggingface.co/$modelId",
    showRunAgainButton = false,
)

class ImageGenTask @Inject constructor() : CustomTask {
    override val task: Task = Task(
        id = BuiltInTaskId.IMAGE_GEN,
        label = "Image Gen",
        category = Category.IMAGE_GEN,
        icon = Icons.Outlined.AutoAwesome,
        description = "Generate images from text prompts using on-device Stable Diffusion",
        shortDescription = "On-device image generation",
        models = mutableListOf(
            sdModel(
                name = "SD 1.5 Q4_0",
                modelId = "second-state/stable-diffusion-v1-5-GGUF",
                modelFile = "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
                commitHash = "main",
                description = "Stable Diffusion 1.5 Q4_0 quantization (~2.1 GB). Good balance of quality and size for on-device generation.",
                sizeInBytes = 2_127_000_000L,
            ),
            sdModel(
                name = "SD 1.5 Q8_0",
                modelId = "second-state/stable-diffusion-v1-5-GGUF",
                modelFile = "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
                commitHash = "main",
                description = "Stable Diffusion 1.5 Q8_0 quantization (~4.0 GB). Higher quality on devices with sufficient RAM.",
                sizeInBytes = 4_028_000_000L,
            ),
        ),
    )

    override fun initializeModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        systemInstruction: Contents?,
        onDone: (String) -> Unit,
    ) {
        coroutineScope.launch(Dispatchers.Default) {
            val sd = StableDiffusion()
            val modelPath = model.getPath(context)
            val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
            val ok = sd.loadModel(modelPath, nThreads)
            if (ok) {
                model.instance = sd
                onDone("")
            } else {
                onDone("Failed to load Stable Diffusion model from $modelPath")
            }
        }
    }

    override fun cleanUpModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: () -> Unit,
    ) {
        val sd = model.instance as? StableDiffusion ?: run {
            onDone()
            return
        }
        coroutineScope.launch(Dispatchers.Default) {
            sd.freeModel()
            onDone()
        }
    }

    @Composable
    override fun MainScreen(data: Any) {
        val myData = data as CustomTaskData
        ImageGenScreen(modelManagerViewModel = myData.modelManagerViewModel)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object ImageGenTaskModule {
    @Provides
    @IntoSet
    fun provideTask(): CustomTask = ImageGenTask()
}
