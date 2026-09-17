// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.security

import com.google.ai.edge.gallery.skills.NoOpSkillsProvider
import com.google.ai.edge.gallery.tools.LoadSkillTool
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins today's fully-ungated in-app tools: alwaysAllow is true and nothing gates the in-app path. */
class PolicyDecisionUngatedPinningTest {

  @Test
  fun `LoadSkillTool is always allowed with no gate`() {
    assertTrue(LoadSkillTool(NoOpSkillsProvider()).alwaysAllow)
  }
}
