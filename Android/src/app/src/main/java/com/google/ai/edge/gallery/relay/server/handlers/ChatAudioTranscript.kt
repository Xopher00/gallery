// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

// Whisper is a separate engine family (ModelEngine.Whisper) from the chat model, so transcribing
// here never touches the chat model's own load state or busy guard.
package com.google.ai.edge.gallery.relay.server.handlers

import android.content.Context
import com.google.ai.edge.gallery.relay.model.ModelRegistry
import com.google.ai.edge.gallery.relay.runtime.ModelEngine
import com.google.ai.edge.gallery.relay.server.ErrorBody
import com.google.ai.edge.gallery.relay.server.ErrorEnvelope
import com.google.ai.edge.gallery.relay.server.LoadResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val AUDIO_TRANSCRIPT_HEADER = "[Audio transcript]"
private const val AUDIO_TRANSCRIPT_FOOTER = "[End of audio transcript]"

/** Appends one labeled block per transcript, in order, after any typed text. */
internal fun appendAudioTranscriptBlocks(text: String, transcripts: List<String>): String {
    if (transcripts.isEmpty()) return text
    val blocks = transcripts.joinToString("\n") { transcript ->
        "$AUDIO_TRANSCRIPT_HEADER\n$transcript\n$AUDIO_TRANSCRIPT_FOOTER"
    }
    return if (text.isEmpty()) blocks else "$text\n$blocks"
}

/**
 * Transcribes [audioClips] in order with the first downloaded whisper model, loading it on
 * demand exactly like /v1/audio/transcriptions does. Responds to [call] and returns null on error.
 */
internal suspend fun transcribeAudioClips(
    call: ApplicationCall,
    context: Context,
    modelRegistry: ModelRegistry,
    audioClips: List<ParsedAudioClip>,
    loadModel: suspend (String, String?) -> LoadResult,
): List<String>? {
    if (audioClips.isEmpty()) return emptyList()

    val downloadedWhisperModel = modelRegistry.getAllModels().firstOrNull {
        modelRegistry.engineOf(it) == ModelEngine.Whisper && modelRegistry.isModelDownloaded(it)
    }
    if (downloadedWhisperModel == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "No speech-to-text model is downloaded")))
        return null
    }

    // NotAWhisperModel/Unavailable can only happen if downloadedWhisperModel stops being a
    // downloaded whisper model between the check above and here; treated as "none downloaded".
    val engine = when (val result = resolveWhisperEngine(modelRegistry, downloadedWhisperModel.name, loadModel)) {
        is WhisperEngineResult.Ok -> result.engine
        is WhisperEngineResult.LoadFailed -> {
            respondLoadError(call, result.loadResult)
            return null
        }
        is WhisperEngineResult.NotAWhisperModel, is WhisperEngineResult.Unavailable -> {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "No speech-to-text model is downloaded")))
            return null
        }
    }

    val transcripts = mutableListOf<String>()
    try {
        for (clip in audioClips) {
            var tempFile: File? = null
            try {
                tempFile = File.createTempFile("chat-audio", ".audio", context.cacheDir)
                tempFile.writeBytes(clip.bytes)
                val samples = withContext(Dispatchers.Default) {
                    AudioDecoder.decodeTo16kMono(tempFile)
                }
                val text = withContext(Dispatchers.Default) {
                    engine.transcribe(samples, "en")
                }
                transcripts.add(text)
            } finally {
                tempFile?.delete()
            }
        }
    } catch (e: UnsupportedAudioFormatException) {
        call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = e.message ?: "Unsupported audio format")))
        return null
    }
    return transcripts
}
