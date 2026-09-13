// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAudioTranscriptTest {

    @Test
    fun `typed text plus one transcript appends a single labeled block`() {
        val result = appendAudioTranscriptBlocks("hello", listOf("world"))

        assertEquals("hello\n[Audio transcript]\nworld\n[End of audio transcript]", result)
    }

    @Test
    fun `two transcripts appear as two blocks in order`() {
        val result = appendAudioTranscriptBlocks("", listOf("first", "second"))

        assertEquals(
            "[Audio transcript]\nfirst\n[End of audio transcript]\n" +
                "[Audio transcript]\nsecond\n[End of audio transcript]",
            result
        )
    }

    @Test
    fun `empty transcript list returns the original text unchanged`() {
        val result = appendAudioTranscriptBlocks("hello", emptyList())

        assertEquals("hello", result)
    }
}
