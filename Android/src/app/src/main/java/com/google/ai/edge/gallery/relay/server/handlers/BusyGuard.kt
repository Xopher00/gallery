// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * R2: one busy-guard abstraction for every API handler that serializes access to a single-slot
 * native resource (an LLM conversation, a StableDiffusion instance, a WhisperEngine, a MediaPipe
 * wrapper, an ML Kit recognizer) behind a per-key kotlinx.coroutines Mutex.
 *
 * Before this file, the same block was copy-pasted across six call sites (AudioTranscription-
 * Handler, ImageGenerationHandler, VisionHandler's detect/segment, OcrHandler, AgentHandler, and
 * OpenAiServer.kt's chat/completions paths), carrying two real defects:
 *
 *  1. TOCTOU: `if (mutex.isLocked) return 429` followed by `mutex.withLock { ... }`. Between the
 *     check and the acquire, a third request can slip past the check and then BLOCK on withLock
 *     instead of getting an immediate 429 -- it silently queues. [Mutex.tryLock] makes the
 *     check-and-acquire a single atomic operation: either you get the lock right now, or you get
 *     Busy right now.
 *
 *  2. Orphaned native work on client disconnect: only a TimeoutCancellationException used to
 *     trigger a cancel (e.g. a native abort call on timeout). If the CLIENT
 *     disconnects instead, Ktor cancels the handler coroutine, [block] unwinds via a plain
 *     CancellationException, and the mutex is released -- but the native call already kicked off
 *     (generateImageNative, whisper_full, LLM generation) keeps running to completion on a
 *     Default-dispatcher thread, burning CPU with nobody left to receive the result. [onCancel]
 *     now runs in a `finally` whenever [block] did NOT complete normally -- timeout, plain
 *     cancellation, or any other exception -- not only on timeout.
 *
 * Mutex-map ownership: the maps stay where they already lived (instance fields on OpenAiServer,
 * or passed down into the handlers/ files as ConcurrentHashMap<String, Mutex> parameters) --
 * withBusyGuard takes the caller's map + a key rather than owning a map itself, since several
 * unrelated instance types (LLM models, SD, Whisper, MediaPipe, ML Kit) already need independent
 * maps and that ownership split predates this refactor.
 *
 * This file has no Ktor dependency on purpose: it returns a small sealed result and lets each
 * call site map Busy/TimedOut to whatever status code and message wording that route already
 * used, so migrating a handler to this helper never has to change what a client sees on the wire.
 */
package com.google.ai.edge.gallery.relay.server.handlers

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/**
 * Sentinel for call sites that had NO request-level timeout before this refactor (the
 * chat/completions family in OpenAiServer.kt just ran under the mutex until the model finished or
 * the client disconnected). Passing this to [withBusyGuard] preserves that "no timeout" behavior
 * -- it is not expected to ever actually elapse -- rather than introducing a new 504 status these
 * routes never returned. Real per-route timeouts (image gen, transcription, vision, OCR, agent
 * run) keep their existing finite values unchanged.
 */
const val NO_BUSY_GUARD_TIMEOUT_MS = Long.MAX_VALUE

/**
 * P6: bound for an on-demand model load triggered implicitly by a request naming a model that
 * isn't loaded yet (ChatHandler/ImageGenerationHandler/AudioTranscriptionHandler's on-demand
 * `loadModel` call, wired up in OpenAiServer.start()'s routing block). Before this, those calls
 * shared [NO_BUSY_GUARD_TIMEOUT_MS] with every other on-demand loadModel() invocation, so an
 * HTTP request waited as long as native model init happened to take -- unbounded. This does NOT
 * change the explicit POST /v1/models/{id}/load route (still [NO_BUSY_GUARD_TIMEOUT_MS] by
 * default) nor the busy-vs-not-busy [Mutex.tryLock] semantics ([BusyResult.Busy] / 429) that
 * apply before this timeout is ever reached.
 */
const val ON_DEMAND_MODEL_LOAD_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes

/** Result of a [withBusyGuard] call. Deliberately Ktor-agnostic -- the caller decides the HTTP
 *  status/body for [Busy] and [TimedOut]; [Ok] carries whatever [block] returned (commonly Unit,
 *  since most call sites call `call.respond(...)` themselves inside [block]). */
sealed class BusyResult<out T> {
    data class Ok<T>(val value: T) : BusyResult<T>()
    object Busy : BusyResult<Nothing>()
    object TimedOut : BusyResult<Nothing>()
}

/**
 * Runs [block] under the per-[key] Mutex in [mutexes] (created on first use), bounded by
 * [timeoutMs], guaranteeing [onCancel] fires exactly when [block] does NOT complete normally.
 *
 * - Lock is atomic ([Mutex.tryLock]): fails -> [BusyResult.Busy] immediately, no TOCTOU window.
 * - [block] runs inside `withTimeout(timeoutMs)`; a [TimeoutCancellationException] there maps to
 *   [BusyResult.TimedOut] (swallowed here -- it is expected control flow, not an error to log).
 * - [onCancel] runs in a `finally` whenever [block] didn't return normally -- timeout, plain
 *   coroutine cancellation (client disconnect), or [block] throwing something else entirely (that
 *   something-else still propagates to the caller after [onCancel] and the unlock run).
 * - The mutex is always released in the same `finally`, so a caller never has to remember to
 *   unlock on any of block's several exit paths.
 */
suspend fun <T> withBusyGuard(
    mutexes: ConcurrentHashMap<String, Mutex>,
    key: String,
    timeoutMs: Long,
    onCancel: () -> Unit = {},
    block: suspend () -> T,
): BusyResult<T> {
    val mutex = mutexes.getOrPut(key) { Mutex() }
    if (!mutex.tryLock()) return BusyResult.Busy

    var completedNormally = false
    try {
        val result = withTimeout(timeoutMs) { block() }
        completedNormally = true
        return BusyResult.Ok(result)
    } catch (e: TimeoutCancellationException) {
        return BusyResult.TimedOut
    } finally {
        if (!completedNormally) onCancel()
        mutex.unlock()
    }
}
