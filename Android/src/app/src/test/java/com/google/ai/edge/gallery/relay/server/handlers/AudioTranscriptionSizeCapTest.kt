// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_CAP = 1_000

class AudioTranscriptionSizeCapTest {

  private fun readWithHandlerLimit(size: Int): ByteArray {
    val channel = ByteReadChannel(ByteArray(size) { 1 })
    return runBlocking {
      channel.readRemaining(TEST_CAP.toLong() + 1).readByteArray()
    }
  }

  @Test
  fun underCapUploadIsReadInFull() {
    val bytes = readWithHandlerLimit(TEST_CAP - 1)
    assertEquals(TEST_CAP - 1, bytes.size)
    assertFalse(bytes.size > TEST_CAP)
  }

  @Test
  fun exactlyAtCapUploadIsReadInFull() {
    val bytes = readWithHandlerLimit(TEST_CAP)
    assertEquals(TEST_CAP, bytes.size)
    assertFalse(bytes.size > TEST_CAP)
  }

  @Test
  fun overCapUploadIsBoundedNotUnboundedlyBuffered() {
    val bytes = readWithHandlerLimit(TEST_CAP * 10)
    assertEquals(TEST_CAP + 1, bytes.size)
  }

  @Test
  fun sizeClassificationMatchesHandlerThreshold() {
    val overCap = readWithHandlerLimit(TEST_CAP * 10)
    assertTrue(overCap.size > TEST_CAP)

    val underCap = readWithHandlerLimit(TEST_CAP - 1)
    assertFalse(underCap.size > TEST_CAP)
  }
}
