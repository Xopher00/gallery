// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyAuthHttpTest {

    // recordedUris (from an intercept installed before the auth check) proves the
    // double-slash/dot-dot cases reach the server with their raw, unnormalized path.
    private fun authTestApp(
        recordedUris: MutableList<String> = mutableListOf(),
        test: suspend HttpClient.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            intercept(ApplicationCallPipeline.Plugins) { recordedUris.add(call.request.uri) }
            installApiKeyAuth("test-key")
            install(StatusPages) { installOpenAiErrorHandlers() }
            routing {
                get("/health") { call.respond(HttpStatusCode.OK) }
                get("/v1/models") { call.respond(HttpStatusCode.OK) }
                get("/v1/agent/tools") { call.respond(HttpStatusCode.OK) }
                post("/v1/agent/run") { call.respond(HttpStatusCode.OK) }
            }
        }
        client.test()
    }

    @Test
    fun healthWithNoHeaderIs200() = authTestApp {
        assertEquals(HttpStatusCode.OK, get("/health").status)
    }

    @Test
    fun modelsWithNoHeaderIs401() = authTestApp {
        assertEquals(HttpStatusCode.Unauthorized, get("/v1/models").status)
    }

    @Test
    fun modelsWithWrongKeyIs401() = authTestApp {
        val response = get("/v1/models") { header("Authorization", "Bearer wrong-key-000") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun modelsWithNonBearerSchemeIs401() = authTestApp {
        val response = get("/v1/models") { header("Authorization", "notabearertoken") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun modelsWithEmptyBearerTokenIs401() = authTestApp {
        val response = get("/v1/models") { header("Authorization", "Bearer ") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun modelsWithCorrectKeyIs200() = authTestApp {
        val response = get("/v1/models") { header("Authorization", "Bearer test-key") }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun modelsWithLowerCaseSchemeIs200() = authTestApp {
        val response = get("/v1/models") { header("Authorization", "bearer test-key") }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun doubleSlashModelsWithNoHeaderIs401AndPathUnnormalized() {
        val recordedUris = mutableListOf<String>()
        authTestApp(recordedUris) {
            val response = get { url { encodedPath = "//v1/models" } }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
        assertEquals(listOf("//v1/models"), recordedUris)
    }

    @Test
    fun tripleSlashModelsWithNoHeaderIs401() = authTestApp {
        val response = get { url { encodedPath = "///v1/models" } }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun dotDotModelsWithNoHeaderIs401() = authTestApp {
        val response = get { url { encodedPath = "/..//v1/models" } }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun doubleSlashAgentToolsWithNoHeaderIs401() = authTestApp {
        val response = get { url { encodedPath = "//v1/agent/tools" } }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun doubleSlashAgentRunPostWithNoHeaderIs401() = authTestApp {
        val response = post { url { encodedPath = "//v1/agent/run" } }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun unknownPathWithCorrectKeyIs404() = authTestApp {
        val response = get("/v1/does-not-exist") { header("Authorization", "Bearer test-key") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun unauthorizedResponseBodyMentionsInvalidApiKey() = authTestApp {
        val response = get("/v1/models")
        assertTrue(response.bodyAsText().contains("\"message\":\"Invalid or missing API key\""))
    }
}
