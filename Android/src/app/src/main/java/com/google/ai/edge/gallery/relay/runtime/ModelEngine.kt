// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

// Single source of truth for "which engine serves this model" -- replaces several mechanisms
// that each answered this differently.
package com.google.ai.edge.gallery.relay.runtime

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType

enum class EngineFamily { LLM, STABLE_DIFFUSION, WHISPER }

sealed class ModelEngine(val family: EngineFamily, val wireName: String) {
    data object AiCore : ModelEngine(EngineFamily.LLM, "aicore")
    data object LiteRtLm : ModelEngine(EngineFamily.LLM, "litert_lm")
    data object LlamaCpp : ModelEngine(EngineFamily.LLM, "llama_cpp")
    data object StableDiffusion : ModelEngine(EngineFamily.STABLE_DIFFUSION, "stable_diffusion")
    data object Whisper : ModelEngine(EngineFamily.WHISPER, "whisper")
}

// Pre-load only -- never reads Model.instance. taskId = null means "no task context, assume LLM".
fun Model.engineFor(taskId: String?): ModelEngine {
    when (taskId) {
        BuiltInTaskId.IMAGE_GEN -> return ModelEngine.StableDiffusion
        BuiltInTaskId.WHISPER -> return ModelEngine.Whisper
    }
    if (this.runtimeType == RuntimeType.AICORE) {
        return ModelEngine.AiCore
    }
    val fileNameToCheck = if (downloadInfo.imported && downloadInfo.downloadFileName.startsWith("$IMPORTS_DIR/")) {
        downloadInfo.downloadFileName.substringAfter("$IMPORTS_DIR/")
    } else {
        downloadInfo.downloadFileName
    }
    return if (isLlamaCppFile(fileNameToCheck)) ModelEngine.LlamaCpp else ModelEngine.LiteRtLm
}

/** What this model can be benchmarked on. The llama.cpp build here is CPU-only. */
val Model.benchmarkAccelerators: List<Accelerator>
    get() =
        if (engineFor(taskId = null) == ModelEngine.LlamaCpp) listOf(Accelerator.CPU)
        else accelerators

// Absorbed from the deleted InferenceEngineType: the only distinction that ever mattered.
private fun isLlamaCppFile(path: String): Boolean = path.endsWith(".gguf", ignoreCase = true)
