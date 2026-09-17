// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.relay.server.ChatCompletionRequest
import com.google.ai.edge.gallery.relay.server.ErrorBody
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// Server's actual Json config lives inline in OpenAiServer.start(), not reachable from a test;
// only ignoreUnknownKeys matters for decoding here.
private val testJson = Json { ignoreUnknownKeys = true }

class StructuredOutputRequestTest {

    @Test
    fun chatCompletionRequestWithoutResponseFormatDecodesToNull() {
        val request = testJson.decodeFromString(
            ChatCompletionRequest.serializer(),
            """{"model":"m","messages":[]}""",
        )
        assertNull(request.response_format)
    }

    @Test
    fun chatCompletionRequestWithJsonObjectFormatDecodes() {
        val request = testJson.decodeFromString(
            ChatCompletionRequest.serializer(),
            """{"model":"m","messages":[],"response_format":{"type":"json_object"}}""",
        )
        assertEquals("json_object", request.response_format?.type)
        assertNull(request.response_format?.json_schema)
    }

    @Test
    fun chatCompletionRequestWithJsonSchemaFormatDecodes() {
        val request = testJson.decodeFromString(
            ChatCompletionRequest.serializer(),
            """{"model":"m","messages":[],"response_format":{"type":"json_schema",""" +
                """"json_schema":{"name":"x","schema":{"type":"object"}}}}""",
        )
        assertEquals("json_schema", request.response_format?.type)
        assertEquals("x", request.response_format?.json_schema?.name)
        assertNotNull(request.response_format?.json_schema?.schema)
    }

    @Test
    fun jsonSchemaSpecDefaultsStrictToFalseAndNameToNull() {
        val request = testJson.decodeFromString(
            ChatCompletionRequest.serializer(),
            """{"model":"m","messages":[],"response_format":{"type":"json_schema",""" +
                """"json_schema":{"schema":{"type":"object"}}}}""",
        )
        assertNull(request.response_format?.json_schema?.name)
        assertFalse(request.response_format?.json_schema?.strict ?: true)
    }

    @Test
    fun errorBodyWithLastOutputDecodesAndEncodesRoundTrip() {
        val body = ErrorBody(message = "bad json", last_output = "not json")
        val encoded = testJson.encodeToString(ErrorBody.serializer(), body)
        val decoded = testJson.decodeFromString(ErrorBody.serializer(), encoded)
        assertEquals("not json", decoded.last_output)
    }
}
