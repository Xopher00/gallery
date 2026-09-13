// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.relay.server.AnthropicMessagesRequest
import com.google.ai.edge.gallery.relay.server.ChatCompletionRequest
import com.google.ai.edge.gallery.relay.server.CompletionRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Server's actual Json config lives inline in OpenAiServer.start(), not reachable from a test;
// only ignoreUnknownKeys matters for decoding here.
private val testJson = Json { ignoreUnknownKeys = true }

class GpuLayersRequestTest {

    @Test
    fun chatCompletionRequestWithoutGpuLayersDecodesToNull() {
        val request = testJson.decodeFromString(
            ChatCompletionRequest.serializer(),
            """{"model":"m","messages":[]}""",
        )
        assertNull(request.gpu_layers)
    }

    @Test
    fun chatCompletionRequestWithGpuLayersDecodesToValue() {
        val request = testJson.decodeFromString(
            ChatCompletionRequest.serializer(),
            """{"model":"m","messages":[],"gpu_layers":999}""",
        )
        assertEquals(999, request.gpu_layers)
    }

    @Test
    fun completionRequestWithoutGpuLayersDecodesToNull() {
        val request = testJson.decodeFromString(
            CompletionRequest.serializer(),
            """{"model":"m","prompt":"p"}""",
        )
        assertNull(request.gpu_layers)
    }

    @Test
    fun completionRequestWithGpuLayersDecodesToValue() {
        val request = testJson.decodeFromString(
            CompletionRequest.serializer(),
            """{"model":"m","prompt":"p","gpu_layers":999}""",
        )
        assertEquals(999, request.gpu_layers)
    }

    @Test
    fun anthropicMessagesRequestWithoutGpuLayersDecodesToNull() {
        val request = testJson.decodeFromString(
            AnthropicMessagesRequest.serializer(),
            """{"model":"m","max_tokens":16,"messages":[]}""",
        )
        assertNull(request.gpu_layers)
    }

    @Test
    fun anthropicMessagesRequestWithGpuLayersDecodesToValue() {
        val request = testJson.decodeFromString(
            AnthropicMessagesRequest.serializer(),
            """{"model":"m","max_tokens":16,"messages":[],"gpu_layers":999}""",
        )
        assertEquals(999, request.gpu_layers)
    }

    @Test
    fun gpuLayersRangeErrorReturnsNullForNull() {
        assertNull(gpuLayersRangeError(null))
    }

    @Test
    fun gpuLayersRangeErrorReturnsNullForZero() {
        assertNull(gpuLayersRangeError(0))
    }

    @Test
    fun gpuLayersRangeErrorReturnsNullForNineNineNine() {
        assertNull(gpuLayersRangeError(999))
    }

    @Test
    fun gpuLayersRangeErrorReturnsMessageForNegativeOne() {
        assertEquals("gpu_layers must be between 0 and 999", gpuLayersRangeError(-1))
    }

    @Test
    fun gpuLayersRangeErrorReturnsMessageForOneThousand() {
        assertEquals("gpu_layers must be between 0 and 999", gpuLayersRangeError(1000))
    }
}
