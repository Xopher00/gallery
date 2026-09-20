// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

// Reshapes text/image blocks into the OpenAI content-part form so parseMessageContent keeps owning image decoding and caps.
// Unknown block types are an error, never a silent drop: a dropped tool_result would leave the caller waiting forever.
package com.google.ai.edge.gallery.relay.server.handlers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AnthropicToolUse(
    val id: String,
    val name: String,
    val input: JsonObject,
)

data class AnthropicToolResult(
    val toolUseId: String,
    val text: String,
    val isError: Boolean,
)

sealed class AnthropicToolChoice {
    object Auto : AnthropicToolChoice()
    object Any : AnthropicToolChoice()
    object None : AnthropicToolChoice()
    data class Named(val name: String) : AnthropicToolChoice()
}

sealed class AnthropicParseResult {
    data class Ok(
        val role: String,
        val text: String,
        val openAiContent: JsonArray,
        val toolUses: List<AnthropicToolUse>,
        val toolResults: List<AnthropicToolResult>,
    ) : AnthropicParseResult()
    data class Error(val message: String) : AnthropicParseResult()
}

fun anthropicSystemText(system: JsonElement?): String? = flattenAnthropicText(system)

fun parseAnthropicMessage(role: String, content: JsonElement?): AnthropicParseResult {
    val textBuilder = StringBuilder()
    val parts = mutableListOf<JsonObject>()
    val toolUses = mutableListOf<AnthropicToolUse>()
    val toolResults = mutableListOf<AnthropicToolResult>()

    when (content) {
        null, is JsonNull -> {}
        is JsonPrimitive -> {
            textBuilder.append(content.content)
            parts += textPart(content.content)
        }
        is JsonArray -> {
            for (blockEl in content) {
                val block = blockEl as? JsonObject
                    ?: return AnthropicParseResult.Error(
                        "Content block in '$role' message must be a JSON object"
                    )
                val type = (block["type"] as? JsonPrimitive)?.content
                    ?: return AnthropicParseResult.Error(
                        "Content block in '$role' message is missing 'type'"
                    )

                when (type) {
                    "text" -> {
                        val text = (block["text"] as? JsonPrimitive)?.content
                            ?: return AnthropicParseResult.Error(
                                "Content block of type 'text' in '$role' message is missing 'text'"
                            )
                        textBuilder.append(text)
                        parts += textPart(text)
                    }
                    "image" -> {
                        val source = block["source"] as? JsonObject
                            ?: return AnthropicParseResult.Error(
                                "Content block of type 'image' in '$role' message is missing 'source'"
                            )
                        val sourceType = (source["type"] as? JsonPrimitive)?.content
                            ?: return AnthropicParseResult.Error(
                                "Content block of type 'image' in '$role' message has a 'source' missing 'type'"
                            )
                        if (sourceType == "url") {
                            return AnthropicParseResult.Error(
                                "Only base64 image sources are supported; this loopback-only server " +
                                    "never fetches remote image URLs"
                            )
                        }
                        if (sourceType != "base64") {
                            return AnthropicParseResult.Error(
                                "Content block of type 'image' in '$role' message has unsupported " +
                                    "source type '$sourceType' (expected 'base64')"
                            )
                        }
                        val mediaType = (source["media_type"] as? JsonPrimitive)?.content
                            ?: return AnthropicParseResult.Error(
                                "Content block of type 'image' in '$role' message has a base64 " +
                                    "'source' missing 'media_type'"
                            )
                        val data = (source["data"] as? JsonPrimitive)?.content
                            ?: return AnthropicParseResult.Error(
                                "Content block of type 'image' in '$role' message has a base64 " +
                                    "'source' missing 'data'"
                            )
                        parts += buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:$mediaType;base64,$data")
                            })
                        }
                    }
                    "tool_use" -> {
                        val id = (block["id"] as? JsonPrimitive)?.content
                            ?: return AnthropicParseResult.Error(
                                "Content block of type 'tool_use' in '$role' message is missing 'id'"
                            )
                        val name = (block["name"] as? JsonPrimitive)?.content
                            ?: return AnthropicParseResult.Error(
                                "Content block of type 'tool_use' in '$role' message is missing 'name'"
                            )
                        val input = block["input"] as? JsonObject
                            ?: return AnthropicParseResult.Error(
                                "Content block of type 'tool_use' in '$role' message has a " +
                                    "missing or non-object 'input'"
                            )
                        toolUses += AnthropicToolUse(id = id, name = name, input = input)
                    }
                    "tool_result" -> {
                        val toolUseId = (block["tool_use_id"] as? JsonPrimitive)?.content
                            ?: return AnthropicParseResult.Error(
                                "Content block of type 'tool_result' in '$role' message is " +
                                    "missing 'tool_use_id'"
                            )
                        val text = flattenAnthropicText(block["content"]) ?: ""
                        val isError = (block["is_error"] as? JsonPrimitive)?.content
                            ?.toBooleanStrictOrNull() ?: false
                        toolResults += AnthropicToolResult(
                            toolUseId = toolUseId,
                            text = text,
                            isError = isError,
                        )
                    }
                    else -> return AnthropicParseResult.Error(
                        "Unsupported content block type '$type' in '$role' message"
                    )
                }
            }
        }
        else -> return AnthropicParseResult.Error(
            "Unsupported 'content' shape in '$role' message: expected a string or an array of " +
                "content blocks"
        )
    }

    return AnthropicParseResult.Ok(
        role = role,
        text = textBuilder.toString(),
        openAiContent = JsonArray(parts),
        toolUses = toolUses.toList(),
        toolResults = toolResults.toList(),
    )
}

fun anthropicToolChoice(toolChoice: JsonElement?): AnthropicToolChoice {
    if (toolChoice == null || toolChoice is JsonNull) return AnthropicToolChoice.Auto
    return when (toolChoice) {
        is JsonPrimitive -> when (toolChoice.content) {
            "any" -> AnthropicToolChoice.Any
            "none" -> AnthropicToolChoice.None
            else -> AnthropicToolChoice.Auto
        }
        is JsonObject -> when ((toolChoice["type"] as? JsonPrimitive)?.content) {
            "any" -> AnthropicToolChoice.Any
            "none" -> AnthropicToolChoice.None
            "tool" -> {
                val name = (toolChoice["name"] as? JsonPrimitive)?.content
                if (name != null) AnthropicToolChoice.Named(name) else AnthropicToolChoice.Auto
            }
            else -> AnthropicToolChoice.Auto
        }
        else -> AnthropicToolChoice.Auto
    }
}

private fun flattenAnthropicText(element: JsonElement?): String? {
    if (element == null || element is JsonNull) return null
    return when (element) {
        is JsonPrimitive -> element.content
        is JsonArray -> element.mapNotNull { flattenAnthropicText(it) }.joinToString("\n")
        is JsonObject -> (element["text"] as? JsonPrimitive)?.content
    }
}

private fun textPart(text: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
}
