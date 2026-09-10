// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.gallery.common.metrics.MetricsTracker
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CoroutineScope

private const val NOT_A_CHAT_MODEL = "This model produces embeddings; use POST /v1/embeddings, not chat."

// Selected in ModelHelperExt.runtimeHelper for a ModelCapability.EMBEDDING litertlm model. Chat
// members exist only to satisfy LlmModelHelper and report a clean error.
object LiteRtLmEmbeddingModelHelper : LlmModelHelper {
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
  ) {
    try {
      model.instance = LiteRtLmEmbedder.create(model.getPath(context))
      onDone("")
    } catch (e: Exception) {
      onDone(e.message ?: "Failed to load embedding model")
    }
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {}

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    (model.instance as? LiteRtLmEmbedder)?.close()
    model.instance = null
    onDone()
  }

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
  ) {
    onError(NOT_A_CHAT_MODEL)
  }

  override fun stopResponse(model: Model) {}
}
