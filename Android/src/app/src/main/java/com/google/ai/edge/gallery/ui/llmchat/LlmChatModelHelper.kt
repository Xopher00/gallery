/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import com.google.ai.edge.litertlm.Capabilities
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.common.metrics.InferenceStatus
import com.google.ai.edge.gallery.common.metrics.MetricsTracker
import com.google.ai.edge.gallery.common.metrics.asSession
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.DEFAULT_VISION_ACCELERATOR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.THOUGHT_CHANNEL
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.data.supportModelBenchmark
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.CountKind
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.runtime.TokenCount
import com.google.ai.edge.gallery.runtime.TurnTokenUsage
import com.google.ai.edge.gallery.runtime.TurnUsageStore
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGLlmChatModelHelper"

data class LlmModelInstance(val engine: Engine, var conversation: Conversation)

object LlmChatModelHelper : LlmModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
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
    if (model.instance != null) {
      Log.d(TAG, "Model '${model.name}' already initialized in LlmChatModelHelper. Skipping.")
      model.markInitialized()
      onDone("")
      return
    }
    model.markInitializationStarted()
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val visionAccelerator =
      model.getStringConfigValue(
        key = ConfigKeys.VISION_ACCELERATOR,
        defaultValue = DEFAULT_VISION_ACCELERATOR.label,
      )
    val visionBackend =
      when (visionAccelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label, Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.GPU()
      }
    val shouldEnableImage = supportImage
    val shouldEnableAudio = supportAudio
    val preferredBackend =
      when (accelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label, Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.CPU()
      }
    Log.d(TAG, "Preferred backend: $preferredBackend")

    val modelPath = model.getPath(context = context)
    val engineConfig =
      EngineConfig(
        modelPath = modelPath,
        backend = preferredBackend,
        visionBackend = if (shouldEnableImage) visionBackend else null, // must be GPU for Gemma 3n
        audioBackend = if (shouldEnableAudio) Backend.CPU() else null, // must be CPU for Gemma 3n
        maxNumTokens = maxTokens,
        cacheDir =
          if (modelPath.startsWith("/data/local/tmp"))
            context.getExternalFilesDir(null)?.absolutePath
          else null,
      )

    // Check if the model file supports speculative decoding.
    var supportsSpeculativeDecoding = false
    // Check if the model file supports speculative decoding.
    try {
      Capabilities(modelPath).use {
        supportsSpeculativeDecoding = it.hasSpeculativeDecodingSupport()
      }
    } catch (e: Exception) {
      // Ignore exceptions and assume not supported.
    }
    // Create an instance of LiteRT LM engine and conversation.
    try {
      var speculativeDecoding = false
      // Check if the model supports speculative decoding for the given task type and if the
      // speculative decoding is enabled in the settings.
      if (
        supportsSpeculativeDecoding &&
          model.capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING]?.contains(taskId) ==
            true
      ) {
        speculativeDecoding =
          model.getBooleanConfigValue(
            key = ConfigKeys.ENABLE_SPECULATIVE_DECODING,
            defaultValue = false,
          )
      }
      // On: getBenchmarkInfo() is what gives an exact prompt/completion token split per turn.
      val enableBenchmark = true
      ExperimentalFlags.enableBenchmark = enableBenchmark
      ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
      Log.d(TAG, "Speculative decoding enabled: $speculativeDecoding")
      val engine = Engine(engineConfig)
      engine.initialize()
      ExperimentalFlags.enableSpeculativeDecoding = false
      // Stays live past init: the conversation below and every later turn read it.
      ExperimentalFlags.enableBenchmark = enableBenchmark

      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val conversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (preferredBackend is Backend.NPU) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      model.instance = LlmModelInstance(engine = engine, conversation = conversation)
    } catch (e: Exception) {
      val errorMsg = cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error")
      model.markInitializationFailed(errorMsg)
      onDone(errorMsg)
      return
    }
    model.markInitialized()
    onDone("")
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    try {
      Log.d(TAG, "Resetting conversation for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val engine = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val shouldEnableImage = supportImage
      val shouldEnableAudio = supportAudio
      Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")

      val accelerator =
        model.getStringConfigValue(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = Accelerator.GPU.label,
        )
      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val newConversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (accelerator == Accelerator.NPU.label || accelerator == Accelerator.TPU.label) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
            initialMessages = initialMessages,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.conversation = newConversation

      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset conversation", e)
    }
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as LlmModelInstance

    try {
      instance.conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }

    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the engine: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.resetInitialization()

    onDone()
    Log.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? LlmModelInstance ?: return
    try {
      instance.conversation.cancelProcess()
    } catch (e: IllegalStateException) {
      Log.w(TAG, "Conversation is not alive, cannot cancel process", e)
    }
  }

  @OptIn(ExperimentalApi::class) // getBenchmarkInfo, for exact token counts
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
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      onError("LlmModelInstance is not initialized.")
      return
    }

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    // Step 1: Initialize turn telemetry with active Conversation.
    val conversation = instance.conversation
    metricsTracker?.startTurn(conversation.asSession())

    // Token accounting. getTokenCount() is cumulative over the conversation, so the difference
    // across this turn is exact even though the prompt/completion split below is not.
    val turnSequence = TurnUsageStore.begin(model.name)
    val tokensBefore = runCatching { conversation.getTokenCount() }.getOrNull()
    var chunkCount = 0

    fun recordUsage() {
      val turnTotal =
        tokensBefore?.let { before ->
          runCatching { conversation.getTokenCount() }.getOrNull()?.let { it - before }
        }
      // One callback per token is unverified for this engine; upstream's own metrics tracker makes
      // the same assumption. Cross-check: an exact total well above chunkCount means it is wrong.
      val benchmark =
        runCatching { conversation.getBenchmarkInfo() }
          .getOrNull()
          ?.takeIf { it.lastPrefillTokenCount > 0 && it.lastDecodeTokenCount > 0 }
      val usage =
        if (benchmark != null) {
          TurnTokenUsage(
            prompt = TokenCount(benchmark.lastPrefillTokenCount, CountKind.EXACT),
            completion = TokenCount(benchmark.lastDecodeTokenCount, CountKind.EXACT),
            exactTotal = turnTotal,
            turnSequence = turnSequence,
          )
        } else {
          val completion = TokenCount(chunkCount, CountKind.ESTIMATED)
          val prompt =
            turnTotal?.let { TokenCount((it - chunkCount).coerceAtLeast(0), CountKind.ESTIMATED) }
          if (prompt == null) return
          TurnTokenUsage(prompt, completion, exactTotal = turnTotal, turnSequence = turnSequence)
        }
      TurnUsageStore.record(model.name, usage)
    }

    // Step 2: Assemble multimodal prompt attachments (images, audio clips, and text).
    val contents = mutableListOf<Content>()
    for (image in images) {
      contents.add(Content.ImageBytes(image.toPngByteArray()))
    }
    for (audioClip in audioClips) {
      contents.add(Content.AudioBytes(audioClip))
    }
    // Add text after images/audio to ensure proper autoregressive token sequencing.
    if (input.trim().isNotEmpty()) {
      contents.add(Content.Text(input))
    }

    // Step 3: Configure extra runtime parameters (such as thinking reasoning mode).
    val enableThinking = extraContext?.get("enable_thinking") == "true"
    val finalExtraContext: Map<String, Any> =
      (extraContext ?: emptyMap()) + ("enable_thinking" to enableThinking)

    // Step 4: Dispatch asynchronous streaming inference to the native LiteRT-LM engine.
    conversation.sendMessageAsync(
      Contents.of(contents),
      object : MessageCallback {
        override fun onMessage(message: Message) {
          val text = message.toString()
          val thinking = message.channels[THOUGHT_CHANNEL]
          // Record streaming token to lock TTFT on first token and update live metrics.
          metricsTracker?.onNewToken(tokenText = text, thinkingText = thinking)
          chunkCount++
          resultListener(text, false, thinking)
        }

        override fun onDone() {
          // Finalize turn metrics with SUCCESS status.
          val unused =
            metricsTracker?.endTurn(statusCode = InferenceStatus.Code.SUCCESS, errorMessage = null)
          // Record before the done callback so a reader sees usage as soon as it is signalled.
          recordUsage()
          resultListener("", true, null)
        }

        override fun onError(throwable: Throwable) {
          if (throwable is CancellationException) {
            // User or system cancelled inference: reconcile context tokens and mark CANCELLED.
            Log.i(TAG, "The inference is cancelled.")
            val unused = metricsTracker?.cancelTurn()
            recordUsage()
            resultListener("", true, null)
          } else {
            // Engine error or crash: record ERROR status with error message.
            Log.e(TAG, "onError", throwable)
            val unused =
              metricsTracker?.endTurn(
                statusCode = InferenceStatus.Code.ERROR,
                errorMessage = throwable.message ?: "Unknown error",
              )
            onError("Error: ${throwable.message}")
          }
        }
      },
      finalExtraContext,
    )
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
