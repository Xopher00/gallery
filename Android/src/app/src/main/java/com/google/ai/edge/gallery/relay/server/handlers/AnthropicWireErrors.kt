// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server.handlers

import com.google.ai.edge.gallery.relay.server.AnthropicErrorBody
import com.google.ai.edge.gallery.relay.server.AnthropicErrorEnvelope
import com.google.ai.edge.gallery.relay.server.LoadResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

internal suspend fun respondAnthropicError(call: ApplicationCall, status: HttpStatusCode, message: String) {
    val type = when (status.value) {
        400 -> "invalid_request_error"
        401 -> "authentication_error"
        404 -> "not_found_error"
        429 -> "rate_limit_error"
        500 -> "api_error"
        503 -> "overloaded_error"
        else -> "api_error"
    }
    call.respond(status, AnthropicErrorEnvelope(error = AnthropicErrorBody(type = type, message = message)))
}

internal suspend fun respondAnthropicLoadError(call: ApplicationCall, result: LoadResult) {
    when (result) {
        is LoadResult.NotFound -> respondAnthropicError(call, HttpStatusCode.NotFound, result.message)
        is LoadResult.Busy -> respondAnthropicError(call, HttpStatusCode.TooManyRequests, result.message)
        is LoadResult.Conflict -> respondAnthropicError(call, HttpStatusCode.Conflict, result.message)
        is LoadResult.TimedOut -> respondAnthropicError(call, HttpStatusCode.GatewayTimeout, result.message)
        is LoadResult.Error -> respondAnthropicError(call, HttpStatusCode.InternalServerError, result.message)
        is LoadResult.Loaded -> respondAnthropicError(
            call,
            HttpStatusCode.InternalServerError,
            "the model was loaded successfully, but the load result was handled as an error",
        )
    }
}
