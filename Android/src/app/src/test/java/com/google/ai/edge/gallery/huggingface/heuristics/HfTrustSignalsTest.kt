// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.huggingface.heuristics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the known-publisher namespace list behind the trust badge. */
class HfTrustSignalsTest {

  @Test
  fun zaiOrg_isKnownPublisherNamespace_caseInsensitively() {
    assertTrue(isKnownPublisherNamespace("zai-org"))
    assertTrue(isKnownPublisherNamespace("ZAI-ORG"))
  }

  @Test
  fun existingPublishers_remainKnown() {
    listOf(
        "litert-community",
        "google",
        "ggml-org",
        "unsloth",
        "bartowski",
        "mistralai",
        "Qwen",
        "meta-llama",
      )
      .forEach { namespace -> assertTrue(namespace, isKnownPublisherNamespace(namespace)) }
  }

  @Test
  fun unrelatedNamespace_isNotKnown() {
    assertFalse(isKnownPublisherNamespace("random-hf-user"))
    assertFalse(isKnownPublisherNamespace("zai-org-fake"))
    assertFalse(isKnownPublisherNamespace(""))
    assertFalse(isKnownPublisherNamespace("   "))
    assertFalse(isKnownPublisherNamespace(null))
  }
}
