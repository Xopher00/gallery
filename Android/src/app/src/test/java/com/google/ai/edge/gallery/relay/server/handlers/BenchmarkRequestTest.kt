// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val testJson = Json { ignoreUnknownKeys = true }

class BenchmarkRequestTest {

    @Test
    fun emptyObjectDecodesToDefaults() {
        val request = testJson.decodeFromString(BenchmarkRequest.serializer(), "{}")
        assertEquals(128, request.prefill_tokens)
        assertEquals(64, request.decode_tokens)
        assertEquals(1, request.runs)
    }

    @Test
    fun prefillTokensZeroReturnsMessage() {
        assertEquals(
            "prefill_tokens must be between 1 and 4096",
            benchmarkRequestError(BenchmarkRequest(prefill_tokens = 0)),
        )
    }

    @Test
    fun prefillTokensFortyOhNineSevenReturnsMessage() {
        assertEquals(
            "prefill_tokens must be between 1 and 4096",
            benchmarkRequestError(BenchmarkRequest(prefill_tokens = 4097)),
        )
    }

    @Test
    fun prefillTokensFortyOhNineSixReturnsNull() {
        assertNull(benchmarkRequestError(BenchmarkRequest(prefill_tokens = 4096)))
    }

    @Test
    fun decodeTokensZeroReturnsMessage() {
        assertEquals(
            "decode_tokens must be between 1 and 1024",
            benchmarkRequestError(BenchmarkRequest(decode_tokens = 0)),
        )
    }

    @Test
    fun decodeTokensTenTwentyFiveReturnsMessage() {
        assertEquals(
            "decode_tokens must be between 1 and 1024",
            benchmarkRequestError(BenchmarkRequest(decode_tokens = 1025)),
        )
    }

    @Test
    fun decodeTokensTenTwentyFourReturnsNull() {
        assertNull(benchmarkRequestError(BenchmarkRequest(decode_tokens = 1024)))
    }

    @Test
    fun runsZeroReturnsMessage() {
        assertEquals(
            "runs must be between 1 and 5",
            benchmarkRequestError(BenchmarkRequest(runs = 0)),
        )
    }

    @Test
    fun runsSixReturnsMessage() {
        assertEquals(
            "runs must be between 1 and 5",
            benchmarkRequestError(BenchmarkRequest(runs = 6)),
        )
    }

    @Test
    fun runsFiveReturnsNull() {
        assertNull(benchmarkRequestError(BenchmarkRequest(runs = 5)))
    }

    @Test
    fun defaultsReturnNull() {
        assertNull(benchmarkRequestError(BenchmarkRequest()))
    }
}
