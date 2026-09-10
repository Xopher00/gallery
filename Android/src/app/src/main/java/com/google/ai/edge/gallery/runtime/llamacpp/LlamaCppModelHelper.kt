// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.runtime.llamacpp

import com.google.ai.edge.gallery.runtime.CountKind
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.TokenCount
import com.google.ai.edge.gallery.runtime.TurnTokenUsage
import com.google.ai.edge.gallery.runtime.TurnUsageStore

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.common.metrics.MetricsTracker
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolProvider
import com.jegly.offlineLLM.smollm.SmolLM
import kotlinx.coroutines.CoroutineScope

private const val TAG = "BoxLlamaCppModelHelper"

/**
 * Box: LlmModelHelper implementation backed by llama.cpp for GGUF models.
 * Routes through the smollm JNI bridge.
 */
object LlamaCppModelHelper : LlmModelHelper {

    // Indexed by model name
    private val engines: MutableMap<String, LlamaCppEngine> = mutableMapOf()

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
        val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
        val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
        val temperature = model.getFloatConfigValue(
            key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE
        )

        val modelPath = model.getPath(context = context)
        Log.d(TAG, "Initializing llama.cpp engine for: $modelPath")

        val engine = LlamaCppEngine()
        engines[model.name] = engine

        val params = SmolLM.InferenceParams(
            temperature = temperature,
            topP = topP,
            topK = topK,
            numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
        )

        engine.loadModel(
            modelPath = modelPath,
            params = params,
            onSuccess = {
                // Store a marker so the ViewModel knows the model is ready
                model.instance = engine
                onDone("")
            },
            onError = { e ->
                Log.e(TAG, "Failed to load GGUF model", e)
                onDone(e.message ?: "Failed to load GGUF model")
            }
        )
    }

    override fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemInstruction: Contents?,
        tools: List<ToolProvider>,
        enableConversationConstrainedDecoding: Boolean,
        initialMessages: List<Message>,
    ) {
        val engine = engines[model.name] ?: return
        val modelPath = engine.lastModelPath ?: return

        Log.d(TAG, "Resetting conversation for ${model.name} (keeping model loaded)")

        // Map litertlm.Message role/content onto the (role, text) pairs
        // LlamaCppEngine.resetConversation seats as a conversation prefix via
        // instance.addChatMessage -- same role strings ("user"/"assistant") the native chat
        // template already expects (see smollm.cpp / SmolLM.addChatMessage callers).
        val conversationHistory = initialMessages.mapNotNull { message ->
            val role = when (message.role) {
                Role.USER -> "user"
                Role.MODEL -> "assistant"
                else -> return@mapNotNull null
            }
            val text = message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
            role to text
        }

        engine.resetConversation(
            modelPath = modelPath,
            params = engine.lastLoadParams ?: SmolLM.InferenceParams(),
            systemPrompt = engine.lastSystemPrompt,
            conversationHistory = conversationHistory,
            onSuccess = {
                // Update model instance reference
                model.instance = engine
                Log.d(TAG, "Conversation reset complete for ${model.name}")
            },
            onError = { e ->
                Log.e(TAG, "Failed to reset conversation for ${model.name}", e)
            },
        )
    }

    override fun cleanUp(model: Model, onDone: () -> Unit) {
        val engine = engines.remove(model.name)
        engine?.unloadModel()
        model.instance = null
        onDone()
        Log.d(TAG, "Clean up done for ${model.name}")
    }

    override fun stopResponse(model: Model) {
        val engine = engines[model.name]
        engine?.stopGeneration()
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
        val engine = engines[model.name]
        if (engine == null || !engine.isModelLoaded.get()) {
            onError("llama.cpp engine not initialized for ${model.name}")
            return
        }

        // Note: llama.cpp text-only — images/audio not supported in this path
        if (images.isNotEmpty()) {
            Log.w(TAG, "Image input not supported with llama.cpp engine, ignoring ${images.size} images")
        }

        // Both counts are exact here: the KV-cache difference covers the whole turn, and every
        // flow emission is one decoded token, so the prompt side is what remains.
        val turnSequence = TurnUsageStore.begin(model.name)
        val contextBefore = engine.contextLengthUsed()

        fun recordUsage(result: LlamaCppEngine.GenerationResult?) {
            val completion = result?.pieceCount ?: 0
            val contextAfter = result?.contextLengthUsed ?: engine.contextLengthUsed()
            val turnTotal = (contextAfter - contextBefore).coerceAtLeast(completion)
            TurnUsageStore.record(
                model.name,
                TurnTokenUsage(
                    prompt = TokenCount((turnTotal - completion).coerceAtLeast(0), CountKind.EXACT),
                    completion = TokenCount(completion, CountKind.EXACT),
                    exactTotal = turnTotal,
                    turnSequence = turnSequence,
                ),
            )
        }

        engine.generateResponse(
            query = input,
            onToken = { partialResponse ->
                resultListener(partialResponse, false, null)
            },
            onComplete = { result ->
                recordUsage(result)
                // Send the final delta (empty string) with done=true
                resultListener("", true, null)
            },
            onCancelled = {
                recordUsage(null)
                resultListener("", true, null)
            },
            onError = { e ->
                Log.e(TAG, "Inference error", e)
                onError(e.message ?: "Inference error")
            }
        )
    }
}
