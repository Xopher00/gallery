// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

// Qwen2.5-Instruct prompt-level tool-call protocol: the llama.cpp C API takes no `tools` parameter
// and only @Tool-reflected ToolProviders are accepted, so schemas go into the system prompt as text.
package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.relay.server.AnthropicTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed class QwenScanEvent {
    data class Text(val text: String) : QwenScanEvent()
    data class ToolCall(val name: String, val args: JsonObject) : QwenScanEvent()
    data class Malformed(val raw: String) : QwenScanEvent()
}

private const val TOOL_CALL_OPEN = "<tool_call>"
private const val TOOL_CALL_CLOSE = "</tool_call>"
private const val TOOL_RESPONSE_OPEN = "<tool_response>"
private const val TOOL_RESPONSE_CLOSE = "</tool_response>"
private const val ERROR_PREFIX = "Error: "

fun renderQwenToolsPreamble(
    tools: List<AnthropicTool>,
    toolChoice: AnthropicToolChoice,
): String {
    val builder = StringBuilder()
    builder.append("# Tools\n\n")
    builder.append("You may call one or more functions to assist with the user query.\n\n")
    builder.append(
        "You are provided with function signatures within <tools></tools> XML tags:\n<tools>\n"
    )
    for (tool in tools) {
        builder.append(renderQwenToolSignature(tool)).append('\n')
    }
    builder.append("</tools>\n\n")
    builder.append(
        "For each function call, return a json object with function name and arguments within " +
            "<tool_call></tool_call> XML tags:\n<tool_call>\n" +
            "{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>"
    )
    when (toolChoice) {
        is AnthropicToolChoice.Any -> builder.append("\n\n")
            .append("You must call one of the tools listed above.")
        is AnthropicToolChoice.Named -> builder.append("\n\n")
            .append("You must call the tool named \"${toolChoice.name}\".")
        else -> {}
    }
    return builder.toString()
}

fun renderAssistantToolCalls(toolUses: List<AnthropicToolUse>): String =
    toolUses.joinToString("\n") { toolUse ->
        TOOL_CALL_OPEN + "\n" +
            buildJsonObject {
                put("name", toolUse.name)
                put("arguments", toolUse.input)
            }.toString() +
            "\n" + TOOL_CALL_CLOSE
    }

fun renderToolResults(results: List<AnthropicToolResult>): String =
    results.joinToString("\n") { result ->
        val body = if (result.isError) ERROR_PREFIX + result.text else result.text
        TOOL_RESPONSE_OPEN + "\n" + body + "\n" + TOOL_RESPONSE_CLOSE
    }

fun parseQwenToolCalls(raw: String): List<QwenScanEvent> {
    val scanner = QwenToolCallScanner()
    val events = mutableListOf<QwenScanEvent>()
    events += scanner.accept(raw)
    events += scanner.finish()
    return events
}

private fun renderQwenToolSignature(tool: AnthropicTool): String {
    val schema = tool.input_schema
    val parameters = if (schema == null || schema is JsonNull) {
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        }
    } else {
        schema
    }
    val description = tool.description
    return buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", tool.name)
                if (description != null) {
                    put("description", description)
                }
                put("parameters", parameters)
            },
        )
    }.toString()
}

class QwenToolCallScanner {
    private val buffer = StringBuilder()
    private var insideToolCall = false

    fun accept(delta: String): List<QwenScanEvent> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        val events = mutableListOf<QwenScanEvent>()
        while (true) {
            if (insideToolCall) {
                val closeIndex = buffer.indexOf(TOOL_CALL_CLOSE)
                if (closeIndex < 0) break
                val body = buffer.substring(0, closeIndex)
                buffer.delete(0, closeIndex + TOOL_CALL_CLOSE.length)
                insideToolCall = false
                events += parseToolCallBody(body)
            } else {
                val openIndex = buffer.indexOf(TOOL_CALL_OPEN)
                if (openIndex < 0) {
                    emitOutsideText(events)
                    break
                }
                if (openIndex > 0) {
                    events += QwenScanEvent.Text(buffer.substring(0, openIndex))
                }
                buffer.delete(0, openIndex + TOOL_CALL_OPEN.length)
                insideToolCall = true
            }
        }
        return events
    }

    fun finish(): List<QwenScanEvent> {
        val events = mutableListOf<QwenScanEvent>()
        if (insideToolCall) {
            events += QwenScanEvent.Malformed(buffer.toString())
            insideToolCall = false
        } else if (buffer.isNotEmpty()) {
            events += QwenScanEvent.Text(buffer.toString())
        }
        buffer.clear()
        return events
    }

    private fun emitOutsideText(events: MutableList<QwenScanEvent>) {
        val held = heldOpenPrefixLength()
        val emitLength = buffer.length - held
        if (emitLength > 0) {
            events += QwenScanEvent.Text(buffer.substring(0, emitLength))
            buffer.delete(0, emitLength)
        }
    }

    private fun heldOpenPrefixLength(): Int {
        val longest = minOf(buffer.length, TOOL_CALL_OPEN.length - 1)
        for (length in longest downTo 1) {
            if (endsWithOpenPrefix(length)) return length
        }
        return 0
    }

    private fun endsWithOpenPrefix(length: Int): Boolean {
        val offset = buffer.length - length
        for (i in 0 until length) {
            if (buffer[offset + i] != TOOL_CALL_OPEN[i]) return false
        }
        return true
    }

    // Safety invariant: consume streamed deltas only. The final accumulated string passes through
    // cleanModelOutput's Regex("<.*$") scrub (isFinal = true), which would delete </tool_call>.
    private fun parseToolCallBody(body: String): QwenScanEvent {
        val element = try {
            Json.parseToJsonElement(body.trim())
        } catch (_: Exception) {
            null
        }
        val obj = element as? JsonObject ?: return QwenScanEvent.Malformed(body)
        val nameElement = obj["name"] as? JsonPrimitive
        if (nameElement == null || !nameElement.isString) {
            return QwenScanEvent.Malformed(body)
        }
        val arguments = when (val rawArguments = obj["arguments"]) {
            is JsonObject -> rawArguments
            is JsonPrimitive -> parseArgumentsString(rawArguments)
            else -> JsonObject(emptyMap())
        }
        return QwenScanEvent.ToolCall(name = nameElement.content, args = arguments)
    }

    private fun parseArgumentsString(rawArguments: JsonPrimitive): JsonObject {
        if (!rawArguments.isString) return JsonObject(emptyMap())
        val element = try {
            Json.parseToJsonElement(rawArguments.content)
        } catch (_: Exception) {
            null
        }
        return element as? JsonObject ?: JsonObject(emptyMap())
    }
}
