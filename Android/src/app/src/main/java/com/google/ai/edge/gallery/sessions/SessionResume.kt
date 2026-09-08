package com.google.ai.edge.gallery.sessions

import android.util.Log
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SessionResume"

/** A resumed session's id plus its persisted message history. */
data class ResumedSession(val sessionId: String, val messages: List<ChatMessageProto>)

private suspend fun loadSessionMessages(
  sessionManager: LlmSessionManager,
  sessionId: String,
  model: Model,
  taskId: String,
  supportImage: Boolean,
  supportAudio: Boolean,
  defaultSystemPrompt: String?,
): List<ChatMessageProto> =
  withContext(Dispatchers.IO) {
    sessionManager.loadSession(
      sessionId = sessionId,
      model = model,
      taskId = taskId,
      supportImage = supportImage,
      supportAudio = supportAudio,
      defaultSystemPrompt = defaultSystemPrompt,
    )
  }

/** Headless session lookup + load: no ViewModel/UI types, so callers own all UI state writes. */
suspend fun resumeSession(
  repository: ChatSessionRepository?,
  sessionManager: LlmSessionManager,
  fallbackSessions: List<ChatSessionProto>,
  taskId: String,
  model: Model,
  explicitSessionId: String?,
  autoResumeSession: Boolean,
  supportImage: Boolean,
  supportAudio: Boolean,
  defaultSystemPrompt: String?,
): ResumedSession? {
  // historySessions.value can race the DataStore's first emission (empty initialValue) and this
  // effect's keys don't change on that emission, so a bare .value read can miss a real session.
  val allSessions = repository?.getAllChatSessions() ?: fallbackSessions
  val taskSessions = allSessions.filter { it.taskId == taskId }
  val sessionToResume =
    (if (explicitSessionId != null) {
        taskSessions.firstOrNull { it.sessionId == explicitSessionId }
      } else if (autoResumeSession) {
        // Already sorted by timestampMs DESC (DefaultChatSessionRepository): first match wins.
        taskSessions.firstOrNull { it.originalModel == model.name }
      } else {
        null
      })
      ?: return null

  Log.d(TAG, "Resuming session: ${sessionToResume.sessionId} (model=${model.name})")
  val protoMessages =
    loadSessionMessages(
      sessionManager = sessionManager,
      sessionId = sessionToResume.sessionId,
      model = model,
      taskId = taskId,
      supportImage = supportImage,
      supportAudio = supportAudio,
      defaultSystemPrompt = defaultSystemPrompt,
    )
  return ResumedSession(sessionToResume.sessionId, protoMessages)
}

/** API mode: id is the client's verbatim, unlooked-up -- unknown ids seat empty history, so this never returns null. */
suspend fun openSession(
  sessionManager: LlmSessionManager,
  sessionId: String,
  taskId: String,
  model: Model,
  supportImage: Boolean,
  supportAudio: Boolean,
  defaultSystemPrompt: String?,
): ResumedSession {
  val protoMessages =
    loadSessionMessages(
      sessionManager = sessionManager,
      sessionId = sessionId,
      model = model,
      taskId = taskId,
      supportImage = supportImage,
      supportAudio = supportAudio,
      defaultSystemPrompt = defaultSystemPrompt,
    )
  return ResumedSession(sessionId, protoMessages)
}
