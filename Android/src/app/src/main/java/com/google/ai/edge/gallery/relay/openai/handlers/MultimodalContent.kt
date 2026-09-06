/*
 * F4: parses the OpenAI chat-message `content` field, which may be either a plain string or an
 * array of content parts ({"type":"text",...} / {"type":"image_url",...}). Kept separate from
 * OpenAiServer.kt so the request-parsing/decoding logic (and its size caps) is easy to audit on
 * its own.
 *
 * Deliberately NOT supported: fetching remote http(s) image_url values. This server binds
 * 127.0.0.1 and must not make outbound requests -- only `data:image/<type>;base64,<...>` URIs are
 * accepted; anything else is a 400.
 */
package com.google.ai.edge.gallery.relay.openai.handlers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Max images accepted in a single request's content-parts array. */
const val MAX_IMAGES_PER_MESSAGE = 4

/** Max decoded (raw bitmap byte) size per image. */
const val MAX_IMAGE_DECODED_BYTES = 10 * 1024 * 1024 // 10 MB

data class ParsedMessageContent(val text: String, val images: List<Bitmap>)

sealed class ContentParseResult {
    data class Ok(val parsed: ParsedMessageContent) : ContentParseResult()
    data class Error(val message: String) : ContentParseResult()
}

private val DATA_URI_RE = Regex(
    "^data:image/(png|jpe?g|webp);base64,(.+)$",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/**
 * Parses a chat message's `content` field. `content == null` is treated as empty text (matches
 * prior behavior for messages that only carried e.g. tool_calls).
 *
 * Plain-string content (the pre-F4 shape) is passed through unchanged -- this is the backward
 * compatibility guarantee: existing text-only clients see no behavior change.
 */
fun parseMessageContent(content: JsonElement?): ContentParseResult {
    if (content == null) {
        return ContentParseResult.Ok(ParsedMessageContent(text = "", images = emptyList()))
    }

    return when (content) {
        is JsonPrimitive -> {
            // Plain string content -- unchanged from pre-F4 behavior.
            ContentParseResult.Ok(ParsedMessageContent(text = content.content, images = emptyList()))
        }
        is JsonArray -> parseContentParts(content)
        else -> ContentParseResult.Error(
            "Unsupported 'content' shape: expected a string or an array of content parts"
        )
    }
}

private fun parseContentParts(parts: JsonArray): ContentParseResult {
    val textBuilder = StringBuilder()
    val images = mutableListOf<Bitmap>()

    for (partEl in parts) {
        val part = try {
            partEl.jsonObject
        } catch (e: Exception) {
            return ContentParseResult.Error("Each content part must be a JSON object")
        }

        when (part["type"]?.jsonPrimitive?.content) {
            "text" -> {
                val text = part["text"]?.jsonPrimitive?.content
                    ?: return ContentParseResult.Error("Content part of type 'text' is missing 'text'")
                textBuilder.append(text)
            }
            "image_url" -> {
                if (images.size >= MAX_IMAGES_PER_MESSAGE) {
                    return ContentParseResult.Error(
                        "Too many images in one request: max $MAX_IMAGES_PER_MESSAGE per message"
                    )
                }
                val url = part["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    ?: return ContentParseResult.Error(
                        "Content part of type 'image_url' is missing 'image_url.url'"
                    )

                if (!url.startsWith("data:")) {
                    return ContentParseResult.Error(
                        "Only base64 data URIs are supported for image_url (e.g. " +
                            "'data:image/png;base64,<...>'); remote http(s) URLs are not fetched " +
                            "by this loopback-only server"
                    )
                }

                val match = DATA_URI_RE.find(url)
                    ?: return ContentParseResult.Error(
                        "Unsupported data URI: expected 'data:image/<png|jpeg|webp>;base64,<...>'"
                    )
                val base64Data = match.groupValues[2]

                val bytes = try {
                    Base64.decode(base64Data, Base64.DEFAULT)
                } catch (e: Exception) {
                    return ContentParseResult.Error("Failed to decode base64 image data: ${e.message}")
                }

                if (bytes.size > MAX_IMAGE_DECODED_BYTES) {
                    return ContentParseResult.Error(
                        "Image too large: ${bytes.size} bytes exceeds the " +
                            "$MAX_IMAGE_DECODED_BYTES byte cap per image"
                    )
                }

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return ContentParseResult.Error("Could not decode image data as a bitmap")

                images.add(bitmap)
            }
            else -> {
                // Unknown part type -- ignore rather than fail, matching OpenAI's general
                // "ignore fields you don't recognize" leniency for forward-compat part types.
            }
        }
    }

    if (images.size > MAX_IMAGES_PER_MESSAGE) {
        return ContentParseResult.Error(
            "Too many images in one request: max $MAX_IMAGES_PER_MESSAGE per message"
        )
    }

    return ContentParseResult.Ok(ParsedMessageContent(text = textBuilder.toString(), images = images))
}
