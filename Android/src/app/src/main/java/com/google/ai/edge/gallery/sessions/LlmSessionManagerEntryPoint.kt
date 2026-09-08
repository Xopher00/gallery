package com.google.ai.edge.gallery.sessions

import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager

// Mirrors ModelRegistryEntryPoint (relay/modelmanager/ModelRegistry.kt) -- lets
// OpenAiServerService reach this @Singleton with no Activity/ViewModel to inject through.
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface LlmSessionManagerEntryPoint {
  fun llmSessionManager(): LlmSessionManager
}
