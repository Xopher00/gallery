// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server

import com.google.ai.edge.gallery.relay.server.handlers.ContextLengthExceededException
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException

// SECURITY: strips the request body from kotlinx.serialization's exception message before it reaches the wire.
private fun sanitizeBadRequestMessage(cause: BadRequestException): String {
    val raw = cause.cause?.message ?: cause.message ?: return "Malformed request body"
    val reason = raw.substringBefore("\nJSON input:").trim()
    return reason.ifBlank { "Malformed request body" }
}

// Safe to catch Throwable: the auth check and busy guards always respond directly, never throw.
internal fun StatusPagesConfig.installOpenAiErrorHandlers() {
    exception<BadRequestException> { call, cause ->
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorEnvelope(ErrorBody(message = sanitizeBadRequestMessage(cause)))
        )
    }
    exception<CannotTransformContentToTypeException> { call, _ ->
        call.respond(
            HttpStatusCode.UnsupportedMediaType,
            ErrorEnvelope(ErrorBody(message = "Request body is missing or could not be parsed as JSON"))
        )
    }
    exception<UnsupportedMediaTypeException> { call, _ ->
        val expectedType = if (call.request.path() == "/v1/audio/transcriptions") {
            "multipart/form-data"
        } else {
            "application/json"
        }
        call.respond(
            HttpStatusCode.UnsupportedMediaType,
            ErrorEnvelope(ErrorBody(message = "Unsupported content type; expected $expectedType"))
        )
    }
    exception<ContextLengthExceededException> { call, cause ->
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorEnvelope(ErrorBody(message = cause.message ?: "", code = "context_length_exceeded"))
        )
    }
    exception<Throwable> { call, cause ->
        if (cause is CancellationException) {
            throw cause
        }
        call.respond(
            HttpStatusCode.InternalServerError,
            ErrorEnvelope(ErrorBody(message = "Internal server error", type = "server_error"))
        )
    }
}
