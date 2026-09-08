// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.ui.whisper

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.whisper.WhisperEngine
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

// Model URLs sourced from ggerganov/whisper.cpp on HuggingFace
private fun whisperModel(
    name: String,
    modelFile: String,
    description: String,
    sizeInBytes: Long,
): Model = Model(
    name = name,
    info = description,
    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$modelFile?download=true",
    sizeInBytes = sizeInBytes,
    downloadFileName = modelFile,
    version = "main",
    showRunAgainButton = false,
)

class WhisperTask @Inject constructor() : CustomTask {
    override val task: Task = Task(
        id = BuiltInTaskId.WHISPER,
        label = "Voice Input",
        category = Category.VOICE,
        icon = Icons.Outlined.Mic,
        description = "On-device speech-to-text using Whisper. Fully offline — audio never leaves your device.",
        shortDescription = "On-device speech transcription",
        models = mutableListOf(
            whisperModel(
                name = "Whisper Tiny (English)",
                modelFile = "ggml-tiny.en.bin",
                description = "Fastest, English only (~75 MB). Best for quick transcription.",
                sizeInBytes = 75_000_000L,
            ),
            whisperModel(
                name = "Whisper Tiny",
                modelFile = "ggml-tiny.bin",
                description = "Fast, multilingual (~75 MB).",
                sizeInBytes = 75_000_000L,
            ),
            whisperModel(
                name = "Whisper Base (English)",
                modelFile = "ggml-base.en.bin",
                description = "Better accuracy, English only (~142 MB).",
                sizeInBytes = 142_000_000L,
            ),
            whisperModel(
                name = "Whisper Base",
                modelFile = "ggml-base.bin",
                description = "Better accuracy, multilingual (~142 MB).",
                sizeInBytes = 142_000_000L,
            ),
            whisperModel(
                name = "Whisper Small (English)",
                modelFile = "ggml-small.en.bin",
                description = "High accuracy, English only (~466 MB).",
                sizeInBytes = 466_000_000L,
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
            val engine = WhisperEngine()
            val modelPath = model.getPath(context)
            val ok = engine.loadModel(modelPath)
            if (ok) {
                model.instance = engine
                onDone("")
            } else {
                onDone("Failed to load Whisper model from $modelPath")
            }
        }
    }

    override fun cleanUpModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: () -> Unit,
    ) {
        val engine = model.instance as? WhisperEngine ?: run { onDone(); return }
        coroutineScope.launch(Dispatchers.Default) {
            engine.freeModel()
            onDone()
        }
    }

    @Composable
    override fun MainScreen(data: Any) {
        val myData = data as CustomTaskData
        WhisperScreen(modelManagerViewModel = myData.modelManagerViewModel)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object WhisperTaskModule {
    @Provides
    @IntoSet
    fun provideTask(): CustomTask = WhisperTask()
}
