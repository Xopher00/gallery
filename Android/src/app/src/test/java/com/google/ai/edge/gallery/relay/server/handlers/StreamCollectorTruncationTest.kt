// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.runtime.TurnUsageStore
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamCollectorTruncationTest {

  private val model = Model(name = "fake")

  @Before
  fun setUp() {
    TurnUsageStore.clear(model.name)
  }

  private fun streamFinishChunk(deltas: List<String>, completionTokens: Int, maxOutputTokens: Int?): String {
    val channel = ByteChannel()
    runBlocking {
      collectInferenceStream(
        writer = channel,
        model = model,
        prompt = "hi",
        encodeFinishChunk = { truncated -> if (truncated) "FINISH:truncated" else "FINISH:stop" },
        maxOutputTokens = maxOutputTokens,
        helper = FakeLlmModelHelper(deltas, completionTokens),
        encodeChunk = { text -> "CHUNK:$text" },
      )
    }
    channel.close()
    val received = StringBuilder()
    runBlocking {
      while (true) {
        val line = channel.readUTF8Line() ?: break
        received.append(line).append('\n')
      }
    }
    return received.toString()
  }

  @Test
  fun streamTruncatedWhenTokensReachCap() {
    val output = streamFinishChunk(deltas = List(40) { "a" }, completionTokens = 40, maxOutputTokens = 40)
    assertTrue(output.contains("FINISH:truncated"))
    assertTrue(output.contains("[DONE]"))
  }

  @Test
  fun streamNotTruncatedWhenUnderCap() {
    val output = streamFinishChunk(deltas = List(12) { "a" }, completionTokens = 12, maxOutputTokens = 40)
    assertTrue(output.contains("FINISH:stop"))
  }

  @Test
  fun streamTruncatedWhenDeltasSuppressed() {
    val output = streamFinishChunk(deltas = listOf("a", "b", "c"), completionTokens = 40, maxOutputTokens = 40)
    assertTrue(output.contains("FINISH:truncated"))
  }

  @Test
  fun streamNotTruncatedWhenNoCap() {
    val output = streamFinishChunk(deltas = List(500) { "a" }, completionTokens = 500, maxOutputTokens = null)
    assertTrue(output.contains("FINISH:stop"))
  }

  @Test
  fun streamTruncatedWhenTokensExceedCap() {
    val output = streamFinishChunk(deltas = List(45) { "a" }, completionTokens = 45, maxOutputTokens = 40)
    assertTrue(output.contains("FINISH:truncated"))
  }
}
