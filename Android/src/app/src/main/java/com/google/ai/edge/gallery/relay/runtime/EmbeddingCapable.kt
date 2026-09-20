// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

// Side interface for a Model.instance that can also produce embeddings; checked the same way
// ImageGenerationHandler checks `model.instance as StableDiffusion`.
interface EmbeddingCapable {
    suspend fun embed(text: String): EmbeddingResult
}

data class EmbeddingResult(val vector: FloatArray, val promptTokens: Int?)
