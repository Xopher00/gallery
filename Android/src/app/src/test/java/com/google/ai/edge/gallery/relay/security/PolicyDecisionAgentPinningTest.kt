// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.security

import com.google.ai.edge.gallery.relay.server.handlers.DEFAULT_ALLOWED_TOOLS
import com.google.ai.edge.gallery.relay.server.handlers.isToolAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests pinning the current behavior of [isToolAllowed] in AgentHandler.kt, so a
 * future PolicyEngine.decide() refactor has to reproduce these exact answers.
 */
class PolicyDecisionAgentPinningTest {

  @Test
  fun `a default tool called by its exact camelCase name is allowed`() {
    assertTrue(isToolAllowed("turnOnFlashlight", DEFAULT_ALLOWED_TOOLS))
  }

  @Test
  fun `a default tool called by its snake_case litertlm name is allowed`() {
    assertTrue(isToolAllowed("turn_on_flashlight", DEFAULT_ALLOWED_TOOLS))
  }

  @Test
  fun `a risky tool not in the default set is denied`() {
    assertFalse(isToolAllowed("sendSms", DEFAULT_ALLOWED_TOOLS))
  }

  @Test
  fun `a custom allowed set without the tool denies it even for a default tool`() {
    assertFalse(isToolAllowed("turnOnFlashlight", setOf("someOtherTool")))
  }

  @Test
  fun `normalization applies on the non-default-set branch too`() {
    assertTrue(isToolAllowed("turn_on_flashlight", setOf("turnOnFlashlight")))
  }
}
