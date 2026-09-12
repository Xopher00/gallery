// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestValidationTest {

    @Test
    fun replyCapErrorReturnsNullForNull() {
        assertEquals(null, replyCapError(null))
    }

    @Test
    fun replyCapErrorReturnsNullForOne() {
        assertEquals(null, replyCapError(1))
    }

    @Test
    fun replyCapErrorReturnsMessageForZero() {
        assertEquals("max_tokens must be at least 1", replyCapError(0))
    }

    @Test
    fun replyCapErrorReturnsMessageForNegativeFive() {
        assertEquals("max_tokens must be at least 1", replyCapError(-5))
    }

    @Test
    fun samplerRangeErrorReturnsNullWhenAllNull() {
        assertEquals(null, samplerRangeError(null, null, null))
    }

    @Test
    fun samplerRangeErrorReturnsNullForTopKOne() {
        assertEquals(null, samplerRangeError(null, null, 1))
    }

    @Test
    fun samplerRangeErrorReturnsMessageForTopKZero() {
        assertEquals("top_k must be at least 1", samplerRangeError(null, null, 0))
    }

    @Test
    fun samplerRangeErrorReturnsMessageForTopKNegativeThree() {
        assertEquals("top_k must be at least 1", samplerRangeError(null, null, -3))
    }

    @Test
    fun samplerRangeErrorReturnsNullForTopPZero() {
        assertEquals(null, samplerRangeError(null, 0.0f, null))
    }

    @Test
    fun samplerRangeErrorReturnsNullForTopPOne() {
        assertEquals(null, samplerRangeError(null, 1.0f, null))
    }

    @Test
    fun samplerRangeErrorReturnsMessageForTopPNegative() {
        assertEquals("top_p must be between 0 and 1", samplerRangeError(null, -0.01f, null))
    }

    @Test
    fun samplerRangeErrorReturnsMessageForTopPTwo() {
        assertEquals("top_p must be between 0 and 1", samplerRangeError(null, 2.0f, null))
    }

    @Test
    fun samplerRangeErrorReturnsNullForTemperatureZero() {
        assertEquals(null, samplerRangeError(0.0f, null, null))
    }

    @Test
    fun samplerRangeErrorReturnsNullForTemperatureNinetyNine() {
        assertEquals(null, samplerRangeError(99.0f, null, null))
    }

    @Test
    fun samplerRangeErrorReturnsMessageForTemperatureNegativeOne() {
        assertEquals("temperature must be at least 0", samplerRangeError(-1.0f, null, null))
    }

    @Test
    fun samplerRangeErrorReportsTopKFirstWhenMultipleAreBad() {
        assertEquals("top_k must be at least 1", samplerRangeError(null, 2.0f, 0))
    }
}
