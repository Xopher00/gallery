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
import com.google.ai.edge.gallery.relay.server.handlers.ContextLengthExceededException
import dagger.hilt.android.EntryPointAccessors
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private const val TAG = "AGOpenAiServer"

// SECURITY: strips the request body from kotlinx.serialization's exception message before it reaches the wire.
private fun sanitizeBadRequestMessage(cause: BadRequestException): String {
    val raw = cause.cause?.message ?: cause.message ?: return "Malformed request body"
    val reason = raw.substringBefore("\nJSON input:").trim()
    return reason.ifBlank { "Malformed request body" }
}

// SECURITY: normalises repeated slashes and ./.. before the public-allowlist check.
private fun normalizePath(rawPath: String): String {
    val collapsed = rawPath.replace(Regex("/+"), "/")
    val resolved = ArrayDeque<String>()
    for (segment in collapsed.split("/")) {
        when (segment) {
            "", "." -> {}
            ".." -> if (resolved.isNotEmpty()) resolved.removeLast()
            else -> resolved.addLast(segment)
        }
    }
    return "/" + resolved.joinToString("/")
}

// SECURITY: requires the RFC 7235 "Bearer" scheme; a bare key with no scheme is rejected.
private fun extractBearerToken(header: String?): String? {
    if (header == null) return null
    val spaceIdx = header.indexOf(' ')
    if (spaceIdx <= 0) return null
    val scheme = header.substring(0, spaceIdx)
    if (!scheme.equals("Bearer", ignoreCase = true)) return null
    val token = header.substring(spaceIdx + 1).trim()
    return token.ifEmpty { null }
}

// SECURITY: constant-time comparison to avoid leaking key-match timing.
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

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

            // SECURITY: deny-by-default -- authenticates every request except "/health";
            // runs before routing so an unknown path gets 401, not a route-leaking 404.
            intercept(ApplicationCallPipeline.Plugins) {
                val normalizedPath = normalizePath(call.request.path())
                if (normalizedPath == "/health") {
                    return@intercept
                }
                val token = extractBearerToken(call.request.headers[HttpHeaders.Authorization])
                if (token == null || !constantTimeEquals(token, apiKey)) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorEnvelope(ErrorBody(message = "Invalid or missing API key"))
                    )
                    finish()
                }
            }

            // Scoped to these specific types only (never Throwable) so it can't swallow
            // the auth 401 or busy-guard 429s.
            install(StatusPages) {
                exception<BadRequestException> { call, cause ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorEnvelope(ErrorBody(message = sanitizeBadRequestMessage(cause)))
                    )
                }
                exception<CannotTransformContentToTypeException> { call, _ ->
                    call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        ErrorEnvelope(ErrorBody(message = "Request body is missing or could not be parsed as JSON"))
                    )
                }
                exception<UnsupportedMediaTypeException> { call, _ ->
                    val expectedType = if (call.request.path() == "/v1/audio/transcriptions") {
                        "multipart/form-data"
                    } else {
                        "application/json"
                    }
                    call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        ErrorEnvelope(ErrorBody(message = "Unsupported content type; expected $expectedType"))
                    )
                }
                exception<ContextLengthExceededException> { call, cause ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorEnvelope(ErrorBody(message = cause.message ?: "", code = "context_length_exceeded"))
                    )
                }
                exception<Throwable> { call, cause ->
                    if (cause is CancellationException) {
                        throw cause
                    }
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorEnvelope(ErrorBody(message = "Internal server error", type = "server_error"))
                    )
                }
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
