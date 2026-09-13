// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import com.google.ai.edge.gallery.data.Accelerator
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelFileDetailsTest {

  private fun copyFixtureToTempFile(name: String): File {
    val tempFile = File.createTempFile("litertlm-header-", ".litertlm")
    tempFile.deleteOnExit()
    javaClass.classLoader!!.getResourceAsStream("litertlm-headers/$name")!!.use { input ->
      tempFile.outputStream().use { output -> input.copyTo(output) }
    }
    return tempFile
  }

  private fun resolveFixture(name: String): ModelFileDetails {
    val originalFileName = name.removeSuffix(".head")
    val tempFile = copyFixtureToTempFile(name)
    return resolveModelFileDetails(fileName = originalFileName, litertlmHeaderPath = tempFile.path)
  }

  @Test
  fun gpuBackendConstraintGivesGpuFromHeader() {
    val details = resolveFixture("gemma-4-E2B-it-gpu.litertlm.head")
    assertEquals(FileDetail(Accelerator.GPU, fromFilename = false), details.accelerator)
  }

  @Test
  fun vendorAndEkvTokensGiveNpuAndContextFromFilename() {
    val details = resolveFixture("Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm.head")
    assertEquals(FileDetail(Accelerator.NPU, fromFilename = true), details.accelerator)
    assertEquals(FileDetail(1280, fromFilename = true), details.contextLength)
  }

  @Test
  fun adapterConstraintsOnlyGiveNoAccelerator() {
    val details = resolveFixture("gemma-4-E2B-it.litertlm.head")
    assertNull(details.accelerator)
  }

  @Test
  fun noHeaderOrFilenameSignalsGiveNulls() {
    val details = resolveFixture("FastVLM-0.5B.litertlm.head")
    assertNull(details.contextLength)
    assertNull(details.accelerator)
  }

  @Test
  fun ggufHeaderContextIsNotMarkedAsFromFilename() {
    val details =
      resolveModelFileDetails(
        fileName = "model.gguf",
        litertlmHeaderPath = "",
        ggufDeclaredContextLength = 4096L,
      )
    assertEquals(FileDetail(4096, fromFilename = false), details.contextLength)
    assertNull(details.accelerator)
  }

  @Test
  fun ggufWithNoDeclaredContextGivesNull() {
    val details =
      resolveModelFileDetails(fileName = "model.gguf", litertlmHeaderPath = "")
    assertNull(details.contextLength)
  }
}
