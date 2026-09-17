// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.common

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Test

private const val EXPECTED_TIMEOUT_MS = 10_000

private class RecordedConnection(url: URL) : HttpURLConnection(url) {
  override fun connect() {}
  override fun disconnect() {}
  override fun usingProxy(): Boolean = false
}

class JsonFetchConnectionTest {

  private fun configuredConnection(): HttpURLConnection {
    val connection = RecordedConnection(URL("http://localhost:1/x"))
    configureJsonFetchConnection(connection)
    return connection
  }

  @Test
  fun connectTimeoutIsBounded() {
    assertEquals(EXPECTED_TIMEOUT_MS, configuredConnection().connectTimeout)
  }

  @Test
  fun readTimeoutIsBounded() {
    assertEquals(EXPECTED_TIMEOUT_MS, configuredConnection().readTimeout)
  }

  @Test
  fun methodIsGet() {
    assertEquals("GET", configuredConnection().requestMethod)
  }
}
