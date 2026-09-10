// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Wraps litertlm's own EmbeddingEngine -- unlike llama.cpp, no native work is needed here.
class LiteRtLmEmbedder private constructor(private val engine: EmbeddingEngine) : EmbeddingCapable {

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        engine.computeEmbedding(listOf(InputData.Text(text))).embedding
    }

    fun close() = engine.close()

    companion object {
        fun create(modelPath: String): LiteRtLmEmbedder {
            // Hardcoded to CPU: no accelerator UI wiring yet (Chat's ConfigKeys.ACCELERATOR was
            // never threaded through here) -- not a correctness requirement.
            val engine = EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelPath, backend = Backend.CPU()))
            engine.initialize()
            return LiteRtLmEmbedder(engine)
        }
    }
}
