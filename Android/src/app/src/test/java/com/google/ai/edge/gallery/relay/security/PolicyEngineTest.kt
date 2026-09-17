package com.google.ai.edge.gallery.relay.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

  private fun execute(toolName: String) = PolicyEngine.Operation.ExecuteTool(toolName)

  @Test
  fun `http agent run allows default-allowed tool without user consent`() {
    val decision =
        PolicyEngine.decide(
            PolicyEngine.Surface.HTTP_AGENT_RUN,
            execute("turnOnFlashlight"),
            PolicyEngine.DEFAULT_ALLOWED_TOOLS,
            userAlreadyAllowed = false,
        )
    assertEquals(PolicyEngine.Decision.Allow, decision)
  }

  @Test
  fun `http agent run denies a tool outside the allowed set and names it in the reason`() {
    val decision =
        PolicyEngine.decide(
            PolicyEngine.Surface.HTTP_AGENT_RUN,
            execute("sendSms"),
            PolicyEngine.DEFAULT_ALLOWED_TOOLS,
            userAlreadyAllowed = false,
        )
    assertTrue(decision is PolicyEngine.Decision.Deny)
    assertTrue((decision as PolicyEngine.Decision.Deny).reason.contains("sendSms"))
  }

  @Test
  fun `in-app mcp allows when already allowed else requires confirmation`() {
    assertEquals(
        PolicyEngine.Decision.Allow,
        PolicyEngine.decide(
            PolicyEngine.Surface.IN_APP_MCP,
            execute("dialNumber"),
            emptySet(),
            userAlreadyAllowed = true,
        ),
    )
    assertEquals(
        PolicyEngine.Decision.RequireUserConfirmation,
        PolicyEngine.decide(
            PolicyEngine.Surface.IN_APP_MCP,
            execute("dialNumber"),
            emptySet(),
            userAlreadyAllowed = false,
        ),
    )
  }

  @Test
  fun `in-app local tool gates runJs behind confirmation until the user allows it`() {
    assertEquals(
        PolicyEngine.Decision.RequireUserConfirmation,
        PolicyEngine.decide(
            PolicyEngine.Surface.IN_APP_LOCAL_TOOL,
            execute("runJs"),
            emptySet(),
            userAlreadyAllowed = false,
        ),
    )
    assertEquals(
        PolicyEngine.Decision.Allow,
        PolicyEngine.decide(
            PolicyEngine.Surface.IN_APP_LOCAL_TOOL,
            execute("runJs"),
            emptySet(),
            userAlreadyAllowed = true,
        ),
    )
  }

  @Test
  fun `in-app local tool leaves tools outside the confirmation set ungated`() {
    assertEquals(
        PolicyEngine.Decision.Allow,
        PolicyEngine.decide(
            PolicyEngine.Surface.IN_APP_LOCAL_TOOL,
            execute("loadSkill"),
            emptySet(),
            userAlreadyAllowed = false,
        ),
    )
  }
}
