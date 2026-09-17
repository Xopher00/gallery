// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.tools

import com.google.ai.edge.gallery.mcp.McpServerState
import com.google.ai.edge.gallery.proto.McpServer
import com.google.ai.edge.gallery.proto.McpTool
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class McpEnabledToolLookupTest {

  private fun mcpTool(name: String, enabled: Boolean): McpTool =
      McpTool.newBuilder().setName(name).setEnabled(enabled).build()

  private fun serverState(enabled: Boolean, tools: List<McpTool>): McpServerState =
      McpServerState(
          McpServer.newBuilder()
              .setUrl("https://mcp.example.test")
              .setEnabled(enabled)
              .addAllTools(tools)
              .build(),
          client = null,
      )

  @Test
  fun enabledToolOnEnabledServerFound() {
    val state = serverState(enabled = true, tools = listOf(mcpTool("weather", enabled = true)))
    assertSame(state, findEnabledToolServer(listOf(state), "weather"))
  }

  @Test
  fun toolOnDisabledServerNotFound() {
    val state = serverState(enabled = false, tools = listOf(mcpTool("weather", enabled = true)))
    assertNull(findEnabledToolServer(listOf(state), "weather"))
  }

  @Test
  fun disabledToolNotFound() {
    val state = serverState(enabled = true, tools = listOf(mcpTool("weather", enabled = false)))
    assertNull(findEnabledToolServer(listOf(state), "weather"))
  }

  @Test
  fun unknownToolNotFound() {
    val state = serverState(enabled = true, tools = listOf(mcpTool("other", enabled = true)))
    assertNull(findEnabledToolServer(listOf(state), "weather"))
  }

  @Test
  fun firstMatchingServerWins() {
    val first = serverState(enabled = true, tools = listOf(mcpTool("weather", enabled = true)))
    val second = serverState(enabled = true, tools = listOf(mcpTool("weather", enabled = true)))
    assertSame(first, findEnabledToolServer(listOf(first, second), "weather"))
  }
}
