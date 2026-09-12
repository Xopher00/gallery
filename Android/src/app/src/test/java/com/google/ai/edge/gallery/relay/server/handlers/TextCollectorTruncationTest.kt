// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.runtime.TurnUsageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TextCollectorTruncationTest {

  private val model = Model(name = "fake")

  @Before
  fun setUp() {
    TurnUsageStore.clear(model.name)
  }

  private fun textTruncated(deltas: List<String>, completionTokens: Int, maxOutputTokens: Int?): Boolean {
    var truncated = false
    runBlocking {
      collectInferenceText(
        model = model,
        prompt = "hi",
        maxOutputTokens = maxOutputTokens,
        onTruncated = { truncated = true },
        helper = FakeLlmModelHelper(deltas, completionTokens),
      )
    }
    return truncated
  }

  @Test
  fun textTruncatedWhenTokensReachCap() {
    assertTrue(textTruncated(deltas = List(40) { "a" }, completionTokens = 40, maxOutputTokens = 40))
  }

  @Test
  fun textNotTruncatedWhenUnderCap() {
    assertFalse(textTruncated(deltas = List(12) { "a" }, completionTokens = 12, maxOutputTokens = 40))
  }

  @Test
  fun textTruncatedWhenDeltasSuppressed() {
    assertTrue(textTruncated(deltas = listOf("a", "b", "c"), completionTokens = 40, maxOutputTokens = 40))
  }

  @Test
  fun textNotTruncatedWhenNoCap() {
    assertFalse(textTruncated(deltas = List(500) { "a" }, completionTokens = 500, maxOutputTokens = null))
  }

  @Test
  fun textTruncatedWhenTokensExceedCap() {
    assertTrue(textTruncated(deltas = List(45) { "a" }, completionTokens = 45, maxOutputTokens = 40))
  }
}
