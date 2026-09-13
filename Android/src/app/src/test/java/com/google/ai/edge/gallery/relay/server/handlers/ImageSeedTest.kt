// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.relay.server.ImageGenerationRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Server's Json config is inline in OpenAiServer.start(), not reachable from a test.
private val testJson = Json { ignoreUnknownKeys = true }

class ImageSeedTest {

    @Test
    fun requestWithoutSeedDecodesWithNullSeed() {
        val request = testJson.decodeFromString(
            ImageGenerationRequest.serializer(),
            """{"prompt": "a cat"}""",
        )
        assertNull(request.seed)
    }

    @Test
    fun requestWithSeedFortyTwoDecodesWithSeedFortyTwo() {
        val request = testJson.decodeFromString(
            ImageGenerationRequest.serializer(),
            """{"prompt": "a cat", "seed": 42}""",
        )
        assertEquals(42L, request.seed)
    }

    @Test
    fun imageSeedMapsNullToNegativeOne() {
        assertEquals(-1L, imageSeed(null))
    }

    @Test
    fun imageSeedMapsFortyTwoToFortyTwo() {
        assertEquals(42L, imageSeed(42L))
    }
}
