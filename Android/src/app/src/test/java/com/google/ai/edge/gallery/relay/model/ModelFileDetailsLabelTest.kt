// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import com.google.ai.edge.gallery.data.Accelerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelFileDetailsLabelTest {

  @Test
  fun headerContextLengthIsUnmarked() {
    val details = ModelFileDetails(contextLength = FileDetail(4096, fromFilename = false))
    val text = buildModelFileDetailsText(details, selectedAccelerators = emptyList())
    assertEquals("Context window: 4096", text)
  }

  @Test
  fun filenameContextLengthIsMarked() {
    val details = ModelFileDetails(contextLength = FileDetail(1280, fromFilename = true))
    val text = buildModelFileDetailsText(details, selectedAccelerators = emptyList())
    assertEquals("Context window: 1280 (from file name)", text)
  }

  @Test
  fun filenameAcceleratorInSelectedPickHasNoSuffix() {
    val details = ModelFileDetails(accelerator = FileDetail(Accelerator.NPU, fromFilename = true))
    val text = buildModelFileDetailsText(details, selectedAccelerators = listOf(Accelerator.NPU))
    assertEquals("Accelerator in file: NPU (from file name)", text)
  }

  @Test
  fun acceleratorNotInSelectedPickGetsNotSelectedSuffix() {
    val details = ModelFileDetails(accelerator = FileDetail(Accelerator.NPU, fromFilename = true))
    val text = buildModelFileDetailsText(details, selectedAccelerators = listOf(Accelerator.GPU))
    assertEquals("Accelerator in file: NPU (from file name), not selected", text)
  }

  @Test
  fun headerAcceleratorIsUnmarked() {
    val details = ModelFileDetails(accelerator = FileDetail(Accelerator.GPU, fromFilename = false))
    val text = buildModelFileDetailsText(details, selectedAccelerators = listOf(Accelerator.GPU))
    assertEquals("Accelerator in file: GPU", text)
  }

  @Test
  fun bothDetailsResolvedJoinsOnTwoLines() {
    val details =
      ModelFileDetails(
        contextLength = FileDetail(1280, fromFilename = true),
        accelerator = FileDetail(Accelerator.NPU, fromFilename = true),
      )
    val text = buildModelFileDetailsText(details, selectedAccelerators = listOf(Accelerator.NPU))
    assertEquals(
      "Context window: 1280 (from file name)\nAccelerator in file: NPU (from file name)",
      text,
    )
  }

  @Test
  fun nothingResolvedGivesNoRow() {
    val text = buildModelFileDetailsText(ModelFileDetails(), selectedAccelerators = emptyList())
    assertNull(text)
  }
}
