// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * Shared collector for turning a model.runtimeHelper.runInference() callback stream into a
 * single final string. Used by every call site that only needs the finished text once
 * generation is done: message-history replay and the non-streaming responses in
 * ChatHandler.kt, the agent's tool-calling turn in AgentHandler.kt, and the quick-action dialog
 * in ProcessTextActivity.kt.
 *
 * The two ChatHandler.kt SSE streaming paths (write partial chunks to the HTTP response as they
 * arrive) stay local to that file instead of moving here: they carry server-only
 * @Serializable DTOs and a Ktor ByteWriteChannel that a UI-layer caller like
 * ProcessTextActivity.kt has no reason to import.
 *
 * `internal` (not `private`) so this is visible module-wide -- same reasoning ChatHandler.kt
 * uses for respondLoadError. Nothing in this function's signature (Model, Bitmap, String,
 * CoroutineScope) is server-specific, so importing it into a UI file does not drag any server
 * plumbing along with it.
 */
package com.google.ai.edge.gallery.relay.server.handlers

import android.graphics.Bitmap
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.relay.runtime.isContextOverflow
import com.google.ai.edge.gallery.runtime.runtimeHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// Distinct type so StatusPages can map it to 400 without string-matching the message again.
internal class ContextLengthExceededException(message: String) : Exception(message)

/**
 * Runs one inference turn and suspends until the runtime reports `done`, returning the
 * concatenated text (or rethrowing whatever runInference's onError reported).
 *
 * `coroutineScope` is the scope handed to runInference itself; it defaults to a fresh
 * Dispatchers.Default scope, matching every server call site below. A caller whose inference
 * needs to be tied to its own lifecycle instead (ProcessTextActivity.kt passes its Compose
 * rememberCoroutineScope()) supplies its own.
 *
 * `onPartial`, when given, is invoked with the text accumulated so far after every non-final
 * chunk, from whatever thread the runtime's resultListener callback fires on (its own callback
 * thread, not this function's caller) -- it exists only so a UI caller can render partial
 * output while generation is still in progress. The server handlers below never pass it and see
 * no behaviour change from its presence.
 */
internal suspend fun collectInferenceText(
    model: Model,
    prompt: String,
    images: List<Bitmap> = emptyList(),
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    onPartial: ((String) -> Unit)? = null,
): String {
    val completer = CompletableDeferred<String>()
    val fullResponse = StringBuilder()

    model.runtimeHelper.runInference(
        model = model,
        input = prompt,
        resultListener = { text, done, _ ->
            if (done) {
                completer.complete(fullResponse.toString())
            } else {
                fullResponse.append(text)
                onPartial?.invoke(fullResponse.toString())
            }
        },
        cleanUpListener = {},
        onError = { completer.completeExceptionally(if (isContextOverflow(it)) ContextLengthExceededException(it) else Exception(it)) },
        images = images,
        audioClips = emptyList(),
        coroutineScope = coroutineScope,
        extraContext = null,
    )

    return completer.await()
}
