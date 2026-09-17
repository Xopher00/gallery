package com.google.ai.edge.gallery.relay.security

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins that loadSkill has no confirmation gate on the in-app local-tool surface. */
class PolicyDecisionUngatedPinningTest {

  @Test
  fun `loadSkill is always allowed with no gate`() {
    assertEquals(
      PolicyEngine.Decision.Allow,
      PolicyEngine.decide(
        PolicyEngine.Surface.IN_APP_LOCAL_TOOL,
        PolicyEngine.Operation.ExecuteTool("loadSkill"),
        allowedTools = emptySet(),
        userAlreadyAllowed = false,
      ),
    )
  }
}
