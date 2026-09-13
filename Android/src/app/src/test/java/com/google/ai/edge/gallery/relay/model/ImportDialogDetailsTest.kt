// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportDialogDetailsTest {

  @Test
  fun ekvAndVendorTagGiveBothDetailsMarked() {
    val text = buildModelFileDetailsTextFromFileName("Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm")
    assertEquals(
      "Context window: 1280 (from file name)\nAccelerator in file: NPU (from file name)",
      text,
    )
  }

  @Test
  fun gpuTokenGivesGpuMarked() {
    val text = buildModelFileDetailsTextFromFileName("gemma-4-E2B-it-gpu.litertlm")
    assertEquals("Accelerator in file: GPU (from file name)", text)
  }

  @Test
  fun noEkvOrVendorTagGivesNoRow() {
    val text = buildModelFileDetailsTextFromFileName("FastVLM-0.5B.litertlm")
    assertNull(text)
  }

  @Test
  fun ggufFileNameGivesNoRow() {
    val text = buildModelFileDetailsTextFromFileName("model-q4.gguf")
    assertNull(text)
  }
}
