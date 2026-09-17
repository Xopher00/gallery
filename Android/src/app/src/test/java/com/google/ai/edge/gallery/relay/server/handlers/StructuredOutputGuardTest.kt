// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.relay.runtime.ModelEngine
import com.google.ai.edge.gallery.relay.server.ResponseFormat
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StructuredOutputGuardTest {

    @Test
    fun streamWithResponseFormatIsAConflict() {
        assertNotNull(
            structuredOutputStreamConflictError(true, ResponseFormat(type = "json_schema")),
        )
    }

    @Test
    fun streamWithoutResponseFormatIsFine() {
        assertNull(structuredOutputStreamConflictError(true, null))
    }

    @Test
    fun nonStreamWithResponseFormatIsFine() {
        assertNull(
            structuredOutputStreamConflictError(false, ResponseFormat(type = "json_schema")),
        )
    }

    @Test
    fun nonStreamWithoutResponseFormatIsFine() {
        assertNull(structuredOutputStreamConflictError(false, null))
    }

    @Test
    fun aiCoreEngineWithResponseFormatIsRejected() {
        assertNotNull(
            structuredOutputEngineError(ModelEngine.AiCore, ResponseFormat(type = "json_schema")),
        )
    }

    @Test
    fun llamaCppEngineWithResponseFormatIsRejected() {
        assertNotNull(
            structuredOutputEngineError(ModelEngine.LlamaCpp, ResponseFormat(type = "json_schema")),
        )
    }

    @Test
    fun liteRtLmEngineWithResponseFormatIsFine() {
        assertNull(
            structuredOutputEngineError(ModelEngine.LiteRtLm, ResponseFormat(type = "json_schema")),
        )
    }

    @Test
    fun anyEngineWithoutResponseFormatIsFine() {
        assertNull(structuredOutputEngineError(ModelEngine.AiCore, null))
    }
}
