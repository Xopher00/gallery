// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery

import android.os.Bundle

// Box: no-op compile seam for upstream's Firebase Analytics call sites — no Firebase dependency,
// no network call. Real analytics stays deleted per STATUS.md's standing decision.
object NoopAnalytics {
  fun logEvent(name: String, params: Bundle? = null) {}

  fun setAnalyticsCollectionEnabled(enabled: Boolean) {}
}

val firebaseAnalytics: NoopAnalytics? = null

enum class GalleryEvent(val id: String) {
  CAPABILITY_SELECT(id = "capability_select"),
  MODEL_DOWNLOAD(id = "model_download"),
  GENERATE_ACTION(id = "generate_action"),
  BUTTON_CLICKED(id = "button_clicked"),
  SKILL_MANAGEMENT(id = "skill_management"),
  SKILL_EXECUTION(id = "skill_execution"),
  CHAT_HISTORY(id = "chat_history"),
  MCP_MANAGEMENT(id = "mcp_management"),
  MCP_EXECUTION(id = "mcp_execution"),
  MODEL_CONFIG_CHANGE(id = "model_config_change"),
}
