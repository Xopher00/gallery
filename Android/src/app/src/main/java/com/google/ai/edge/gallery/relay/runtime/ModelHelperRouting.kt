// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.relay.runtime.llamacpp.LlamaCppModelHelper
import com.google.ai.edge.gallery.runtime.LlmModelHelper

internal val Model.relayRuntimeHelperOrNull: LlmModelHelper?
  get() {
    // GGUF always goes to llama.cpp (it serves chat and embeddings, self-detecting pooling type);
    // the EMBEDDING capability only chooses between the two litertlm helpers.
    if (this.engineFor(taskId = null) == ModelEngine.LlamaCpp) {
      return LlamaCppModelHelper
    }
    if (ModelCapability.EMBEDDING in this.capabilities) {
      return LiteRtLmEmbeddingModelHelper
    }
    return null
  }
