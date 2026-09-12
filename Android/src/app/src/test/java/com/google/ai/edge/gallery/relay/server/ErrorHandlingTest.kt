// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server

import com.google.ai.edge.gallery.relay.server.handlers.ContextLengthExceededException
import com.google.ai.edge.gallery.relay.server.handlers.respondLoadError
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private data class FakeRequest(val value: String)

class ErrorHandlingTest {

    @Test
    fun badRequestExceptionMapsTo400WithMessage() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { installOpenAiErrorHandlers() }
            routing {
                get("/boom") { throw io.ktor.server.plugins.BadRequestException("bad") }
            }
        }
        val response = client.get("/boom")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"message\""))
    }

    @Test
    fun nonJsonBodyAtChatCompletionsMapsTo415() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { installOpenAiErrorHandlers() }
            routing {
                post("/v1/chat/completions") {
                    call.receive<FakeRequest>()
                    call.respond("unreachable")
                }
            }
        }
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Text.Plain)
            setBody("not json")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertTrue(response.bodyAsText().contains("Request body is missing or could not be parsed as JSON"))
    }

    @Test
    fun multipartRouteWithJsonBodyMapsTo415MentioningMultipart() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { installOpenAiErrorHandlers() }
            routing {
                post("/v1/audio/transcriptions") {
                    call.receiveMultipart()
                    call.respond("unreachable")
                }
            }
        }
        val response = client.post("/v1/audio/transcriptions") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertTrue(response.bodyAsText().contains("multipart/form-data"))
    }

    @Test
    fun multipartRouteAtOtherPathMapsTo415MentioningJson() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { installOpenAiErrorHandlers() }
            routing {
                post("/v1/other") {
                    call.receiveMultipart()
                    call.respond("unreachable")
                }
            }
        }
        val response = client.post("/v1/other") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertTrue(response.bodyAsText().contains("application/json"))
    }

    @Test
    fun contextLengthExceededMapsTo400WithCode() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { installOpenAiErrorHandlers() }
            routing {
                get("/boom") { throw ContextLengthExceededException("too long") }
            }
        }
        val response = client.get("/boom")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"code\":\"context_length_exceeded\""))
    }

    @Test
    fun unexpectedExceptionMapsTo500WithoutLeakingDetail() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { installOpenAiErrorHandlers() }
            routing {
                get("/boom") { throw IllegalStateException("secret-detail") }
            }
        }
        val response = client.get("/boom")
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Internal server error"))
        assertTrue(body.contains("\"type\":\"server_error\""))
        assertTrue(!body.contains("secret-detail"))
    }

    @Test
    fun explicitRespondIsNotTurnedInto500ByCatchAll() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(StatusPages) { installOpenAiErrorHandlers() }
            routing {
                get("/busy") {
                    call.respond(HttpStatusCode.TooManyRequests, ErrorEnvelope(ErrorBody(message = "busy")))
                }
            }
        }
        val response = client.get("/busy")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
    }

    @Test
    fun respondLoadErrorNotFoundMapsTo404WithMessage() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/x") { respondLoadError(call, LoadResult.NotFound("m")) }
            }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("\"message\":\"m\""))
    }

    @Test
    fun respondLoadErrorBusyMapsTo429WithMessage() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/x") { respondLoadError(call, LoadResult.Busy("m")) }
            }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertTrue(response.bodyAsText().contains("\"message\":\"m\""))
    }

    @Test
    fun respondLoadErrorConflictMapsTo409WithMessage() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/x") { respondLoadError(call, LoadResult.Conflict("Unload 'x' first")) }
            }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("Unload 'x' first"))
    }

    @Test
    fun respondLoadErrorErrorMapsTo400WithMessage() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/x") { respondLoadError(call, LoadResult.Error("m")) }
            }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("\"message\":\"m\""))
    }

    @Test
    fun respondLoadErrorTimedOutMapsTo503WithMessage() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/x") { respondLoadError(call, LoadResult.TimedOut("m")) }
            }
        }
        val response = client.get("/x")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("\"message\":\"m\""))
    }
}
