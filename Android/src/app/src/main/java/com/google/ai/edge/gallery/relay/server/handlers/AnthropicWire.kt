// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0
package com.google.ai.edge.gallery.relay.server.handlers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun anthropicTextBlock(text: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", text)
}

fun anthropicToolUseBlock(id: String, name: String, input: JsonElement): JsonObject =
    buildJsonObject {
        put("type", "tool_use")
        put("id", id)
        put("name", name)
        put("input", input)
    }

fun anthropicContentBlocks(
    text: String?,
    calls: List<QwenScanEvent.ToolCall>,
    ids: List<String>,
): List<JsonObject> {
    val blocks = mutableListOf<JsonObject>()
    if (!text.isNullOrBlank()) {
        blocks += anthropicTextBlock(text)
    }
    calls.forEachIndexed { index, call ->
        blocks += anthropicToolUseBlock(ids[index], call.name, call.args)
    }
    return blocks
}

fun anthropicStopReason(calls: List<QwenScanEvent.ToolCall>): String =
    if (calls.isNotEmpty()) "tool_use" else "end_turn"

fun anthropicSseFrame(event: String, dataJson: String): String =
    "event: $event\ndata: $dataJson\n\n"
