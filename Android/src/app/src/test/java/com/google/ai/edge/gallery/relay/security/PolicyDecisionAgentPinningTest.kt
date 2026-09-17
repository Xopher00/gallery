// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM tests pinning PolicyEngine.decide()'s HTTP_AGENT_RUN behavior -- the same answers this
 * test pinned against AgentHandler.kt's own isToolAllowed before that logic moved into the engine.
 */
class PolicyDecisionAgentPinningTest {

  private fun decide(toolName: String, allowedTools: Set<String>): PolicyEngine.Decision =
    PolicyEngine.decide(
      PolicyEngine.Surface.HTTP_AGENT_RUN,
      PolicyEngine.Operation.ExecuteTool(toolName),
      allowedTools,
      userAlreadyAllowed = false,
    )

  @Test
  fun `a default tool called by its exact camelCase name is allowed`() {
    assertEquals(
      PolicyEngine.Decision.Allow,
      decide("turnOnFlashlight", PolicyEngine.DEFAULT_ALLOWED_TOOLS),
    )
  }

  @Test
  fun `a default tool called by its snake_case litertlm name is allowed`() {
    assertEquals(
      PolicyEngine.Decision.Allow,
      decide("turn_on_flashlight", PolicyEngine.DEFAULT_ALLOWED_TOOLS),
    )
  }

  @Test
  fun `a risky tool not in the default set is denied`() {
    assertEquals(
      PolicyEngine.Decision.Deny("Tool 'sendSms' is not in the allowed set"),
      decide("sendSms", PolicyEngine.DEFAULT_ALLOWED_TOOLS),
    )
  }

  @Test
  fun `a custom allowed set without the tool denies it even for a default tool`() {
    assertEquals(
      PolicyEngine.Decision.Deny("Tool 'turnOnFlashlight' is not in the allowed set"),
      decide("turnOnFlashlight", setOf("someOtherTool")),
    )
  }

  @Test
  fun `normalization applies on the non-default-set branch too`() {
    assertEquals(
      PolicyEngine.Decision.Allow,
      decide("turn_on_flashlight", setOf("turnOnFlashlight")),
    )
  }
}
