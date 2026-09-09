/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.runtime

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.relay.runtime.LiteRtLmEmbeddingModelHelper
import com.google.ai.edge.gallery.relay.runtime.ModelEngine
import com.google.ai.edge.gallery.relay.runtime.engineFor
import com.google.ai.edge.gallery.runtime.aicore.AICoreModelHelper
import com.google.ai.edge.gallery.runtime.llamacpp.LlamaCppModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper

var testingModelHelper: LlmModelHelper? = null

val Model.runtimeHelper: LlmModelHelper
  get() {
    testingModelHelper?.let {
      return it
    }
    // GGUF embedding models stay on LlamaCppModelHelper -- the native load path self-detects
    // pooling type. litertlm has no such signal, so it needs the declared capability here.
    if (ModelCapability.EMBEDDING in this.capabilities) {
      return LiteRtLmEmbeddingModelHelper
    }
    if (this.runtimeType == RuntimeType.AICORE) {
      return AICoreModelHelper
    }
    if (this.engineFor(taskId = null) == ModelEngine.LlamaCpp) {
      return LlamaCppModelHelper
    }
    return LlmChatModelHelper
  }
