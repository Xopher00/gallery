// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.relay.runtime.llamacpp.LlamaCppModelHelper
import com.google.ai.edge.gallery.runtime.LlmModelHelper

internal val Model.relayRuntimeHelperOrNull: LlmModelHelper?
  get() {
    // GGUF embedding models stay on LlamaCppModelHelper -- the native load path self-detects
    // pooling type. litertlm has no such signal, so it needs the declared capability here.
    if (ModelCapability.EMBEDDING in this.capabilities) {
      return LiteRtLmEmbeddingModelHelper
    }
    if (this.engineFor(taskId = null) == ModelEngine.LlamaCpp) {
      return LlamaCppModelHelper
    }
    return null
  }
