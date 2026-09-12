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
import kotlinx.coroutines.CoroutineScope

/**
 * Scripts llama.cpp's callback shape, where the engine's completion count can differ from the
 * number of [resultListener] callbacks.
 */
internal class FakeLlmModelHelper(
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
