// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.sessions

import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager

// Mirrors ModelRegistryEntryPoint (relay/modelmanager/ModelRegistry.kt) -- lets
// OpenAiServerService reach this @Singleton with no Activity/ViewModel to inject through.
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface LlmSessionManagerEntryPoint {
  fun llmSessionManager(): LlmSessionManager
}
