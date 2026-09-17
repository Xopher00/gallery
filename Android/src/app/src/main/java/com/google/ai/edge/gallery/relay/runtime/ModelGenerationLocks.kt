// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

// Shared across every real inference call site so an API request and an in-app chat turn can't
// race the same model's mutable Conversation/context concurrently. A plain object, not a Hilt
// singleton: OpenAiServer is plain-constructed (OpenAiServerService.kt), not Hilt-managed, so this
// follows the same shape ThermalGovernor already uses for exactly this kind of cross-cutting state.
internal object ModelGenerationLocks {
  val mutexes = ConcurrentHashMap<String, Mutex>()
}
