// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.gallery.common.metrics.MetricsTracker
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.runtime.CountKind
import com.google.ai.edge.gallery.relay.runtime.TokenCount
import com.google.ai.edge.gallery.relay.runtime.TurnTokenUsage
import com.google.ai.edge.gallery.relay.runtime.TurnUsageStore
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plain JVM tests for [collectInferenceText] and [collectInferenceStream]'s truncation decision.
 * [FakeLlmModelHelper] stands in for a real engine, scripting the same callback shape llama.cpp
 * uses -- an engine-recorded completion token count that can diverge from the number of
 * `resultListener` callbacks -- without touching [Model.runtimeHelper] or any Android runtime.
 */
class InferenceCollectorsTest {

  private val model = Model(name = "fake")

  @Before
  fun setUp() {
    TurnUsageStore.clear(model.name)
  }

  private class FakeLlmModelHelper(
    private val deltas: List<String>,
    private val completionTokens: Int,
  ) : LlmModelHelper {
    override fun initialize(
      context: Context,
      model: Model,
      taskId: String,
      supportImage: Boolean,
      supportAudio: Boolean,
      onDone: (String) -> Unit,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
      coroutineScope: CoroutineScope?,
    ) {}

    override fun resetConversation(
      model: Model,
      supportImage: Boolean,
      supportAudio: Boolean,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
      initialMessages: List<Message>,
    ) {}

    override fun cleanUp(model: Model, onDone: () -> Unit) {}

    override fun runInference(
      model: Model,
      input: String,
      resultListener: ResultListener,
      cleanUpListener: CleanUpListener,
      onError: (message: String) -> Unit,
      images: List<Bitmap>,
      audioClips: List<ByteArray>,
      coroutineScope: CoroutineScope?,
      extraContext: Map<String, String>?,
      metricsTracker: MetricsTracker?,
      maxOutputTokens: Int?,
    ) {
      for (delta in deltas) resultListener(delta, false, null)
      TurnUsageStore.record(
        model.name,
        TurnTokenUsage(
          prompt = TokenCount.ZERO_EXACT,
          completion = TokenCount(completionTokens, CountKind.EXACT),
        ),
      )
      resultListener("", true, null)
    }

    override fun stopResponse(model: Model) {}
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
  fun textTruncatedWhenTokensReachCap() {
    assertTrue(textTruncated(deltas = List(40) { "a" }, completionTokens = 40, maxOutputTokens = 40))
  }

  @Test
  fun streamTruncatedWhenTokensReachCap() {
    val output = streamFinishChunk(deltas = List(40) { "a" }, completionTokens = 40, maxOutputTokens = 40)
    assertTrue(output.contains("FINISH:truncated"))
    assertTrue(output.contains("[DONE]"))
  }

  @Test
  fun textNotTruncatedWhenUnderCap() {
    assertFalse(textTruncated(deltas = List(12) { "a" }, completionTokens = 12, maxOutputTokens = 40))
  }

  @Test
  fun streamNotTruncatedWhenUnderCap() {
    val output = streamFinishChunk(deltas = List(12) { "a" }, completionTokens = 12, maxOutputTokens = 40)
    assertTrue(output.contains("FINISH:stop"))
  }

  @Test
  fun textTruncatedWhenDeltasSuppressed() {
    assertTrue(textTruncated(deltas = listOf("a", "b", "c"), completionTokens = 40, maxOutputTokens = 40))
  }

  @Test
  fun streamTruncatedWhenDeltasSuppressed() {
    val output = streamFinishChunk(deltas = listOf("a", "b", "c"), completionTokens = 40, maxOutputTokens = 40)
    assertTrue(output.contains("FINISH:truncated"))
  }

  @Test
  fun textNotTruncatedWhenNoCap() {
    assertFalse(textTruncated(deltas = List(500) { "a" }, completionTokens = 500, maxOutputTokens = null))
  }

  @Test
  fun streamNotTruncatedWhenNoCap() {
    val output = streamFinishChunk(deltas = List(500) { "a" }, completionTokens = 500, maxOutputTokens = null)
    assertTrue(output.contains("FINISH:stop"))
  }

  @Test
  fun textTruncatedWhenTokensExceedCap() {
    assertTrue(textTruncated(deltas = List(45) { "a" }, completionTokens = 45, maxOutputTokens = 40))
  }

  @Test
  fun streamTruncatedWhenTokensExceedCap() {
    val output = streamFinishChunk(deltas = List(45) { "a" }, completionTokens = 45, maxOutputTokens = 40)
    assertTrue(output.contains("FINISH:truncated"))
  }
}
