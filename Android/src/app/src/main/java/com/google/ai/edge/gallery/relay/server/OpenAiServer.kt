// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * Ported from mobile-server (com.server.edge.gallery) into this project (relay).
 *
 * Changes from the source:
 *  - package rewritten to com.google.ai.edge.gallery
 *  - binds to 127.0.0.1 only (was 0.0.0.0)
 *  - inference now goes through model.runtimeHelper.runInference (relay's per-runtime
 *    dispatch: LiteRT-LM / llama.cpp / AICore) instead of a hardcoded LlmChatModelHelper
 *  - bearer API key auth required on all v1 routes; health is open
 */
package com.google.ai.edge.gallery.relay.server

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.DataStoreRepositoryEntryPoint
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import dagger.hilt.android.EntryPointAccessors
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private const val TAG = "AGOpenAiServer"

class OpenAiServer(
    internal val context: Context,
    internal val modelRegistry: ModelRegistry,
    internal val llmSessionManager: com.google.ai.edge.gallery.agent.sessions.LlmSessionManager,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    internal val modelMutexes = ConcurrentHashMap<String, Mutex>()

    internal val dataStoreRepository by lazy {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DataStoreRepositoryEntryPoint::class.java,
        ).dataStoreRepository()
    }

    // Must reflect what's actually bound/authenticated now; checkConfig() compares these
    // against current prefs to decide on a rebind.
    var boundHost: String = ""
        private set
    var apiKeyFingerprint: String = ""
        private set

    // Per-model-name busy-guard maps, one per otherwise-unrelated instance type (LLM chat/
    // completion models use modelMutexes above; these guard Whisper/SD/MediaPipe vision separately).
    internal val transcriptionMutexes = ConcurrentHashMap<String, Mutex>()
    internal val imageGenMutexes = ConcurrentHashMap<String, Mutex>()
    internal val visionDetectMutexes = ConcurrentHashMap<String, Mutex>()
    internal val visionSegmentMutexes = ConcurrentHashMap<String, Mutex>()

    // Separate from modelMutexes: a run reconfigures the model's conversation with
    // MobileActionsTools' ToolSet for its duration and restores it afterwards.
    internal val agentMutexes = ConcurrentHashMap<String, Mutex>()

    // Single fixed-key busy-guard (only one recognizer, unlike the per-model-name maps above).
    internal val ocrMutexes = ConcurrentHashMap<String, Mutex>()

    // Box: the Hexagon NPU accepts only one process/model at a time; Accelerator.TPU
    // shares the same physical slot.
    internal val npuGuardMutex = Mutex()
    @Volatile internal var npuHolderModelName: String? = null

    // Three cases (not a Boolean) so a transient bindHost() failure isn't treated as
    // "config genuinely changed".
    sealed class ConfigCheck {
        object Matches : ConfigCheck()
        object NeedsRebind : ConfigCheck()
        data class BindUnavailable(val message: String) : ConfigCheck()
    }

    // BindUnavailable (rather than propagating the throw) so a transient INTERFACE blip
    // doesn't tear down an otherwise-healthy server.
    fun checkConfig(context: Context): ConfigCheck {
        val currentHost = try {
            ServerRuntime.bindHost()
        } catch (e: Exception) {
            return ConfigCheck.BindUnavailable(e.message ?: "Failed to determine bind host")
        }
        return if (boundHost == currentHost &&
            apiKeyFingerprint == ApiKey.fingerprint(ApiKey.apiKey(context, dataStoreRepository))
        ) {
            ConfigCheck.Matches
        } else {
            ConfigCheck.NeedsRebind
        }
    }

    fun start(port: Int = ServerRuntime.DEFAULT_PORT) {
        if (server != null) return

        val apiKey = ApiKey.apiKey(context, dataStoreRepository)
        val host = ServerRuntime.bindHost()
        boundHost = host
        apiKeyFingerprint = ApiKey.fingerprint(apiKey)

        server = embeddedServer(CIO, port = port, host = host) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true // else fields equal to their default are dropped
                })
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
            }

            installApiKeyAuth(apiKey)

            // Catch-all is safe: auth and busy guards above respond directly, never throw.
            install(StatusPages) {
                installOpenAiErrorHandlers()
            }

            routing {
                installOpenAiRoutes(this@OpenAiServer, port)
            }
        }.start(wait = false)
        ServerRuntime.runningServer = this
        Log.i(TAG, "OpenAI API Server started on $boundHost:$port")
    }

    suspend fun stop() {
        val heldByThisServer = modelRegistry.heldModelNames()
            .filter { HOLDER_API in modelRegistry.holdersOf(it) }
            .toList()
        for (name in heldByThisServer) {
            modelRegistry.releaseHold(name, HOLDER_API)
            unpinIfPinned(name)
            val task = modelRegistry.tasks.find { t -> t.models.any { it.name == name } }
            val model = task?.models?.find { it.name == name }
            if (task != null && model != null) {
                modelRegistry.cleanupModelAwait(context = context, task = task, model = model)
            }
        }
        val embedded = server
        server = null
        // Hop onto Dispatchers.IO so a Dispatchers.Main caller never blocks on the up-to-2s
        // drain, while still awaiting it so ordering-dependent callers are safe.
        if (embedded != null) {
            withContext(Dispatchers.IO) {
                embedded.stop(1000, 2000)
            }
        }
        if (ServerRuntime.runningServer === this) {
            ServerRuntime.runningServer = null
        }
    }
}
