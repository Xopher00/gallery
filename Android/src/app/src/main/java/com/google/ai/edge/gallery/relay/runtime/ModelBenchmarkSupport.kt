// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import com.google.ai.edge.gallery.data.Model

/** Whether this model can be run through the benchmark screen. */
val Model.relaySupportsBenchmark: Boolean
  get() {
    if (!isLlm) return false
    return when (engineFor(taskId = null)) {
      ModelEngine.LlamaCpp -> true
      // engineFor falls back to LiteRtLm for any non-.gguf import, so the runtime check stays:
      // an imported SD or Whisper file must not reach the LiteRT benchmark call.
      ModelEngine.LiteRtLm -> isLiteRtLm
      else -> false
    }
  }
