/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 */

package com.google.ai.edge.gallery.relay.discovery

import com.google.ai.edge.gallery.proto.HfModelItemProto

// Provenance hint only, not a malware/safety guarantee: HF's API exposes no scan status.
private val KNOWN_PUBLISHER_NAMESPACES =
  setOf(
    "litert-community",
    "google",
    "ggml-org",
    "unsloth",
    "bartowski",
    "mistralai",
    "Qwen",
    "meta-llama",
  )

fun HfModelItemProto.isKnownPublisher(): Boolean {
  val namespace = author.ifBlank { id.substringBefore("/", missingDelimiterValue = "") }
  return isKnownPublisherNamespace(namespace)
}

/** Same check as [isKnownPublisher], for callers with only a namespace string (e.g. a parsed URL). */
fun isKnownPublisherNamespace(namespace: String?): Boolean {
  if (namespace.isNullOrBlank()) return false
  return KNOWN_PUBLISHER_NAMESPACES.any { it.equals(namespace, ignoreCase = true) }
}
