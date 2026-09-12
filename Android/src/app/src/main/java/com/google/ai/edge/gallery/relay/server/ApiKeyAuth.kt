// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.security.MessageDigest

// SECURITY: normalises repeated slashes and ./.. before the public-allowlist check.
private fun normalizePath(rawPath: String): String {
    val collapsed = rawPath.replace(Regex("/+"), "/")
    val resolved = ArrayDeque<String>()
    for (segment in collapsed.split("/")) {
        when (segment) {
            "", "." -> {}
            ".." -> if (resolved.isNotEmpty()) resolved.removeLast()
            else -> resolved.addLast(segment)
        }
    }
    return "/" + resolved.joinToString("/")
}

// SECURITY: requires the RFC 7235 "Bearer" scheme; a bare key with no scheme is rejected.
internal fun extractBearerToken(header: String?): String? {
    if (header == null) return null
    val spaceIdx = header.indexOf(' ')
    if (spaceIdx <= 0) return null
    val scheme = header.substring(0, spaceIdx)
    if (!scheme.equals("Bearer", ignoreCase = true)) return null
    val token = header.substring(spaceIdx + 1).trim()
    return token.ifEmpty { null }
}

// SECURITY: constant-time comparison to avoid leaking key-match timing.
private fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

// SECURITY: deny-by-default -- authenticates every request except "/health";
// runs before routing so an unknown path gets 401, not a route-leaking 404.
internal fun Application.installApiKeyAuth(apiKey: String) {
    intercept(ApplicationCallPipeline.Plugins) {
        val normalizedPath = normalizePath(call.request.path())
        if (normalizedPath == "/health") {
            return@intercept
        }
        val token = extractBearerToken(call.request.headers[HttpHeaders.Authorization])
        if (token == null || !constantTimeEquals(token, apiKey)) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorEnvelope(ErrorBody(message = "Invalid or missing API key"))
            )
            finish()
        }
    }
}
