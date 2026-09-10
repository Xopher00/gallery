package com.jegly.offlineLLM.smollm

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

class SmolLM {
    companion object {
        private const val TAG = "SmolLM"

        init {
            val cpuFeatures = getCPUFeatures()
            val hasFp16 = cpuFeatures.contains("fp16") || cpuFeatures.contains("fphp")
            val hasDotProd = cpuFeatures.contains("dotprod") || cpuFeatures.contains("asimddp")
            val hasSve = cpuFeatures.contains("sve")
            val hasI8mm = cpuFeatures.contains("i8mm")
            val isAtLeastArmV82 =
                cpuFeatures.contains("asimd") &&
                    cpuFeatures.contains("crc32") &&
                    cpuFeatures.contains("aes")
            val isAtLeastArmV84 = cpuFeatures.contains("dcpop") && cpuFeatures.contains("uscat")

            val isEmulated =
                (Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu"))

            if (!isEmulated) {
                if (supportsArm64V8a()) {
                    if (isAtLeastArmV84 && hasSve && hasI8mm && hasFp16 && hasDotProd) {
                        System.loadLibrary("smollm_v8_4_fp16_dotprod_i8mm_sve")
                    } else if (isAtLeastArmV84 && hasSve && hasFp16 && hasDotProd) {
                        System.loadLibrary("smollm_v8_4_fp16_dotprod_sve")
                    } else if (isAtLeastArmV84 && hasI8mm && hasFp16 && hasDotProd) {
                        System.loadLibrary("smollm_v8_4_fp16_dotprod_i8mm")
                    } else if (isAtLeastArmV84 && hasFp16 && hasDotProd) {
                        System.loadLibrary("smollm_v8_4_fp16_dotprod")
                    } else if (isAtLeastArmV82 && hasFp16 && hasDotProd) {
                        System.loadLibrary("smollm_v8_2_fp16_dotprod")
                    } else if (isAtLeastArmV82 && hasFp16) {
                        System.loadLibrary("smollm_v8_2_fp16")
                    } else {
                        System.loadLibrary("smollm_v8")
                    }
                } else {
                    System.loadLibrary("smollm")
                }
            } else {
                System.loadLibrary("smollm")
            }
        }

        private fun getCPUFeatures(): String {
            return try {
                File("/proc/cpuinfo").readText()
                    .substringAfter("Features").substringAfter(":").substringBefore("\n").trim()
            } catch (e: FileNotFoundException) {
                ""
            }
        }

        private fun supportsArm64V8a(): Boolean = Build.SUPPORTED_ABIS[0] == "arm64-v8a"
    }

    private var nativePtr = 0L

    data class InferenceParams(
        val minP: Float = 0.1f,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40,
        val repeatPenalty: Float = 1.1f,
        val storeChats: Boolean = true,
        val contextSize: Long? = null,
        val chatTemplate: String? = null,
        val numThreads: Int = 4,
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
    )

    /** One benchmark repetition; the caller repeats and aggregates. */
    data class BenchResult(
        val prefillSeconds: Double,
        val decodeSeconds: Double,
        val prefillTokensPerSecond: Double,
        val decodeTokensPerSecond: Double,
    )

    object DefaultParams {
        const val CONTEXT_SIZE: Long = 2048L
        const val CHAT_TEMPLATE: String =
            "{% for message in messages %}{% if loop.first and messages[0]['role'] != 'system' %}{{ '<|im_start|>system You are a helpful AI assistant.<|im_end|> ' }}{% endif %}{{'<|im_start|>' + message['role'] + ' ' + message['content'] + '<|im_end|>' + ' '}}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant ' }}{% endif %}"
    }

    suspend fun load(modelPath: String, params: InferenceParams = InferenceParams()) =
        withContext(Dispatchers.IO) {
            val ggufReader = GGUFReader()
            ggufReader.load(modelPath)
            // Clamp the GGUF's declared context to something a phone can actually hold. Modern
            // models advertise enormous training contexts -- Qwen3.5-9B declares 262144 -- and
            // honouring that verbatim allocates a KV cache and compute buffers far past what the
            // device has, so lmkd kills the process mid-load. The bigger the weights, the less
            // headroom remains for the cache, so the cap tightens with file size. An explicit
            // caller setting (params.contextSize) still wins.
            // Adapted from jegly/OfflineLLM @ e81091e (Apache-2.0), smollm/SmolLM.kt.
            val fileSizeBytes = File(modelPath).length()
            val maxContextBySize = when {
                fileSizeBytes > 2L * 1024 * 1024 * 1024 -> 4096L // > 2 GB
                fileSizeBytes > 1L * 1024 * 1024 * 1024 -> 8192L // 1-2 GB
                else -> 8192L
            }
            val rawContextSize = ggufReader.getContextSize() ?: DefaultParams.CONTEXT_SIZE
            val modelContextSize = minOf(rawContextSize, maxContextBySize)
            if (modelContextSize < rawContextSize) {
                Log.i(
                    TAG,
                    "Clamped declared context $rawContextSize -> $modelContextSize " +
                        "(model file ${fileSizeBytes / (1024 * 1024)} MB)",
                )
            }
            val modelChatTemplate = ggufReader.getChatTemplate() ?: DefaultParams.CHAT_TEMPLATE
            nativePtr = loadModel(
                modelPath,
                params.minP,
                params.temperature,
                params.topP,
                params.topK,
                params.repeatPenalty,
                params.storeChats,
                params.contextSize ?: modelContextSize,
                params.chatTemplate ?: modelChatTemplate,
                params.numThreads,
                params.useMmap,
                params.useMlock,
            )
        }

    /**
     * Generic method to add a message with a specific role.
     * Essential for models like Gemma that use "model" instead of "assistant".
     */
    fun addChatMessage(role: String, message: String) {
        verifyHandle()
        addChatMessage(nativePtr, message, role)
    }

    fun addUserMessage(message: String) {
        addChatMessage("user", message)
    }

    fun addSystemPrompt(prompt: String) {
        addChatMessage("system", prompt)
    }

    fun addAssistantMessage(message: String) {
        addChatMessage("assistant", message)
    }

    fun getResponseGenerationSpeed(): Float {
        verifyHandle()
        return getResponseGenerationSpeed(nativePtr)
    }

    fun getContextLengthUsed(): Int {
        verifyHandle()
        return getContextSizeUsed(nativePtr)
    }

    fun getResponseAsFlow(query: String): Flow<String> = flow {
        verifyHandle()
        startCompletion(nativePtr, query)
        var piece = completionLoop(nativePtr)
        while (piece != "[EOG]") {
            emit(piece)
            piece = completionLoop(nativePtr)
        }
        stopCompletion(nativePtr)
    }

    fun getResponse(query: String): String {
        verifyHandle()
        startCompletion(nativePtr, query)
        var piece = completionLoop(nativePtr)
        var response = ""
        while (piece != "[EOG]") {
            response += piece
            piece = completionLoop(nativePtr)
        }
        stopCompletion(nativePtr)
        return response
    }

    // Wipes the KV cache -- never run this against a conversation you need to keep.
    fun benchModel(pp: Int, tg: Int, pl: Int = 1): BenchResult {
        verifyHandle()
        val v = benchModel(nativePtr, pp, tg, pl)
        return BenchResult(v[0], v[1], v[2], v[3])
    }

    // Throws if this GGUF has no pooling-type metadata -- see LLMInference::getEmbedding.
    fun getEmbedding(text: String): FloatArray {
        verifyHandle()
        return getEmbedding(nativePtr, text)
    }

    fun close() {
        if (nativePtr != 0L) {
            close(nativePtr)
            nativePtr = 0L
        }
    }

    fun isLoaded(): Boolean = nativePtr != 0L

    private fun verifyHandle() {
        check(nativePtr != 0L) { "Model is not loaded. Call SmolLM.load() first." }
    }

    private external fun loadModel(
        modelPath: String, minP: Float, temperature: Float, topP: Float, topK: Int,
        repeatPenalty: Float, storeChats: Boolean, contextSize: Long, chatTemplate: String,
        nThreads: Int, useMmap: Boolean, useMlock: Boolean
    ): Long

    private external fun addChatMessage(modelPtr: Long, message: String, role: String)
    private external fun getResponseGenerationSpeed(modelPtr: Long): Float
    private external fun getContextSizeUsed(modelPtr: Long): Int
    private external fun close(modelPtr: Long)
    private external fun startCompletion(modelPtr: Long, prompt: String)
    private external fun completionLoop(modelPtr: Long): String
    private external fun stopCompletion(modelPtr: Long)
    private external fun benchModel(modelPtr: Long, pp: Int, tg: Int, pl: Int): DoubleArray
    private external fun getEmbedding(modelPtr: Long, text: String): FloatArray
}
