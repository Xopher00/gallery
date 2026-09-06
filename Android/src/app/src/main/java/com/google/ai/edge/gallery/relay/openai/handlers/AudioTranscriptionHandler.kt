/*
 * WP F1: POST /v1/audio/transcriptions.
 *
 * Decodes an uploaded audio file to 16kHz mono float PCM using Android's built-in
 * MediaExtractor/MediaCodec (no added dependency), then runs it through a loaded
 * WhisperEngine instance. Any container/codec the device's MediaExtractor can demux and for
 * which a MediaCodec decoder exists is supported (WAV, m4a/AAC, MP3, OGG, etc. depending on
 * device codec support) -- decoding failures surface as 400 rather than a generic 500 so a
 * caller can tell "your file" from "our bug".
 */
package com.google.ai.edge.gallery.relay.openai.handlers

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.google.ai.edge.gallery.relay.openai.ErrorBody
import com.google.ai.edge.gallery.relay.openai.ErrorEnvelope
import com.google.ai.edge.gallery.relay.openai.LoadResult
import com.google.ai.edge.gallery.relay.openai.TranscriptionResponse
import com.google.ai.edge.gallery.relay.modelmanager.ModelRegistry
import com.google.ai.edge.gallery.whisper.WhisperEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.readRemaining
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray

private class UnsupportedAudioFormatException(message: String) : Exception(message)

private const val SUPPORTED_FORMATS_MSG =
    "wav, m4a/aac, mp3, ogg (any container/codec combination the device's MediaExtractor/" +
        "MediaCodec can demux and decode)"

// Transcription is far faster than image generation, and WhisperEngine.cancelTranscription()
// aborts in about a second once asked -- but a pathological or very long upload must still not
// be able to hang a Ktor worker indefinitely. Mirrors ImageGenerationHandler's timeout pattern.
private const val TRANSCRIPTION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

suspend fun handleAudioTranscriptions(
    call: ApplicationCall,
    context: Context,
    modelRegistry: ModelRegistry,
    mutexes: ConcurrentHashMap<String, Mutex>,
    loadModel: suspend (String, String?) -> LoadResult,
) {
    var tempFile: File? = null
    try {
        var modelName: String? = null
        var language: String? = null
        var responseFormat = "json"
        var fileBytes: ByteArray? = null

        val multipart = call.receiveMultipart()
        multipart.forEachPart { part ->
            when (part) {
                is PartData.FormItem -> {
                    when (part.name) {
                        "model" -> modelName = part.value
                        "language" -> language = part.value
                        "response_format" -> responseFormat = part.value
                        // "prompt" / "temperature" accepted per the OpenAI shape but
                        // WhisperEngine.transcribe() has no equivalent knob -- ignored.
                    }
                }
                is PartData.FileItem -> {
                    if (part.name == "file") {
                        fileBytes = part.provider().readRemaining().readByteArray()
                    }
                }
                else -> {}
            }
            part.dispose()
        }

        if (fileBytes == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Missing required 'file' field")))
            return
        }
        val requestedModel = modelName
        if (requestedModel.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "Missing required 'model' field")))
            return
        }
        if (responseFormat != "json" && responseFormat != "text") {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorEnvelope(ErrorBody(message = "Unsupported response_format '$responseFormat'. Supported: json, text"))
            )
            return
        }

        val whisperModels = modelRegistry.tasks
            .flatMap { it.models }
            .filter { it.instance is WhisperEngine }
            .distinctBy { it.name }

        var model = whisperModels.find { it.name == requestedModel }

        // WP: not currently loaded as a WhisperEngine instance -- try loading it on demand
        // before giving up (matches ChatHandler's pattern).
        if (model == null) {
            when (val result = loadModel(requestedModel, null)) {
                is LoadResult.Loaded -> {
                    val loaded = modelRegistry.tasks
                        .flatMap { it.models }
                        .find { it.name == requestedModel }
                    if (loaded?.instance is WhisperEngine) {
                        model = loaded
                    }
                }
                else -> {
                    respondLoadError(call, result)
                    return
                }
            }
        }

        if (model == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorEnvelope(ErrorBody(
                    message = "Model '$requestedModel' is not loaded for transcription. Available: " +
                        whisperModels.joinToString(", ") { it.name }.ifEmpty { "(none loaded)" }
                ))
            )
            return
        }
        val engine = model.instance as WhisperEngine

        tempFile = File.createTempFile("upload", ".audio", context.cacheDir)
        tempFile.writeBytes(fileBytes!!)
        val audioFile = tempFile

        try {
            // Both the MediaExtractor/MediaCodec decode loop and WhisperEngine.transcribe() are
            // synchronous CPU-bound work; run them off the Ktor worker dispatcher so a long
            // upload can't tie one up. withBusyGuard bounds the whole thing (decode + transcribe)
            // by one timeout so an undecodable/enormous file can't stall the decode step itself
            // and hang the request indefinitely, and calls engine.cancelTranscription() -- the
            // real cancel, not a no-op -- whenever this doesn't finish normally, whether that's
            // the timeout below or the client disconnecting mid-transcription.
            val guardResult = withBusyGuard(
                mutexes = mutexes,
                key = model.name,
                timeoutMs = TRANSCRIPTION_TIMEOUT_MS,
                onCancel = { engine.cancelTranscription() },
            ) {
                val samples = withContext(Dispatchers.Default) {
                    AudioDecoder.decodeTo16kMono(audioFile)
                }
                val text = withContext(Dispatchers.Default) {
                    engine.transcribe(samples, language ?: "en")
                }
                if (responseFormat == "text") {
                    call.respondText(text, ContentType.Text.Plain)
                } else {
                    call.respond(TranscriptionResponse(text = text))
                }
            }
            when (guardResult) {
                is BusyResult.Busy -> call.respond(
                    HttpStatusCode.TooManyRequests,
                    ErrorEnvelope(ErrorBody(message = "Model '${model.name}' is busy with another transcription"))
                )
                is BusyResult.TimedOut -> call.respond(
                    HttpStatusCode.GatewayTimeout,
                    ErrorEnvelope(ErrorBody(
                        message = "Transcription exceeded the ${TRANSCRIPTION_TIMEOUT_MS / 60_000} " +
                            "minute timeout and was cancelled."
                    ))
                )
                is BusyResult.Ok -> {}
            }
        } catch (e: UnsupportedAudioFormatException) {
            call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = e.message ?: "Unsupported audio format")))
        }
    } finally {
        tempFile?.delete()
    }
}

/**
 * Decodes an on-disk audio file to 16kHz mono float32 PCM via MediaExtractor + MediaCodec.
 * MediaExtractor sniffs the container from content, not the file extension, so this works for
 * any container the device supports regardless of what the upload was named.
 */
private object AudioDecoder {

    fun decodeTo16kMono(file: File): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (e: Exception) {
            extractor.release()
            throw UnsupportedAudioFormatException(
                "Could not open audio file (${e.message}). Supported formats: $SUPPORTED_FORMATS_MSG"
            )
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw UnsupportedAudioFormatException(
                "No audio track found in upload. Supported formats: $SUPPORTED_FORMATS_MSG"
            )
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            extractor.release()
            throw UnsupportedAudioFormatException(
                "No decoder available on this device for codec '$mime'. Supported formats: $SUPPORTED_FORMATS_MSG"
            )
        }

        val sourceChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1
        val sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else WhisperEngine.SAMPLE_RATE

        val pcm = ArrayList<Short>()
        try {
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            val timeoutUs = 10_000L

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                    if (bufferInfo.size > 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex)!!
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuffer = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val shorts = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(shorts)
                        for (s in shorts) pcm.add(s)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            throw UnsupportedAudioFormatException(
                "Failed to decode audio (${e.message}). Supported formats: $SUPPORTED_FORMATS_MSG"
            )
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val monoSamples = if (sourceChannels <= 1) {
            pcm.toShortArray()
        } else {
            val frames = pcm.size / sourceChannels
            ShortArray(frames) { i ->
                var sum = 0
                for (c in 0 until sourceChannels) sum += pcm[i * sourceChannels + c]
                (sum / sourceChannels).toShort()
            }
        }

        val resampled = if (sourceSampleRate == WhisperEngine.SAMPLE_RATE) {
            monoSamples
        } else {
            resampleLinear(monoSamples, sourceSampleRate, WhisperEngine.SAMPLE_RATE)
        }

        return FloatArray(resampled.size) { resampled[it] / 32768f }
    }

    private fun resampleLinear(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty() || fromRate <= 0) return ShortArray(0)
        val outLength = (input.size.toLong() * toRate / fromRate).toInt()
        val output = ShortArray(outLength)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val idx1 = (idx0 + 1).coerceIn(0, input.size - 1)
            val frac = srcPos - idx0
            val sample = input[idx0] * (1 - frac) + input[idx1] * frac
            output[i] = sample.toInt().toShort()
        }
        return output
    }
}
