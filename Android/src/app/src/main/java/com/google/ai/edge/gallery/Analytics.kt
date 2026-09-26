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
  MODEL_INITIALIZE(id = "model_initialize"),
  INFERENCE_METRICS(id = "inference_metrics"),
}

/** Strongly typed Firebase Analytics parameter keys for inference and model lifecycle telemetry. */
enum class InferenceMetricsParam(val key: String) {
  MODEL_NAME("model_name"),
  MODEL_VERSION("model_version"),
  TASK_ID("task_id"),
  ACCELERATOR("accelerator"),
  STATUS("status"),
  ERROR_CODE("error_code"),
  TURN_INDEX("turn_index"),
  INIT_DURATION_MS("init_duration_ms"),
  SAMPLER_CONFIG("sampler_config"),
  TOTAL_LATENCY_MS("total_latency_ms"),
  TTFT_MS("ttft_ms"),
  DECODE_DURATION_MS("decode_duration_ms"),
  PREFILL_SPEED_TPS("prefill_speed_tps"),
  DECODE_SPEED_TPS("decode_speed_tps"),
  PROMPT_TOKENS("prompt_tokens"),
  OUTPUT_TOKENS("output_tokens"),
  TURN_TOTAL_TOKENS("turn_total_tokens"),
  CONSUMED_CONTEXT_TOKENS("consumed_context_tokens"),
  MAX_CONTEXT_TOKENS("max_context_tokens"),
  PEAK_MEMORY_MB("peak_memory_mb"),
  AVG_MEMORY_MB("avg_memory_mb"),
}
