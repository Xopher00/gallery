/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 *
 * Extracted from `ui/llmchat/LlmChatViewModel.kt` per
 * `reference/2026-09-05-merge-friction-reduction.md` §2.1 (item 5 / rank 5): the ~155 lines of
 * Box chat-persistence logic (conversation lifecycle, message persistence, system-prompt
 * persistence) that were previously inlined into Google's `LlmChatViewModelBase`.
 *
 * Constructor-injected with Hilt (not `@EntryPoint`) so a Google rename or signature change to
 * `ChatRepository` fails the build at compile time rather than silently at runtime — see §4c of
 * the reference doc for why constructor injection is preferred over a runtime lookup here.
 *
 * Deliberately unscoped (no `@Singleton`/`@ViewModelScoped`): each `LlmChatViewModelBase` subclass
 * that injects this gets its own instance, so `currentConversationId` state does not leak between
 * e.g. `LlmChatViewModel` and `AgentChatViewModel`/`LlmAskImageViewModel`/`LlmAskAudioViewModel`.
 */

package com.google.ai.edge.gallery.relay.data.local

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.local.ChatRepository
import com.google.ai.edge.gallery.data.local.entities.Conversation
import com.google.ai.edge.gallery.data.local.entities.Message
import com.google.ai.edge.gallery.security.SecurityUtils
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGChatPersistence"

/**
 * Box: encrypted chat persistence for a single chat view model — conversation lifecycle
 * (create/resume), message persistence, and system-prompt persistence. One instance per
 * `LlmChatViewModelBase` subclass; see the file header for why it is not a singleton.
 */
class ChatPersistence @Inject constructor(private val chatRepository: ChatRepository) {

  private var currentConversationId: String? = null
  private val conversationMutex = Mutex()

  private val _currentSystemPrompt = MutableStateFlow("")
  val currentSystemPrompt: StateFlow<String> = _currentSystemPrompt.asStateFlow()

  fun setCurrentSystemPrompt(prompt: String) {
    _currentSystemPrompt.value = prompt
  }

  fun updateSystemPrompt(scope: CoroutineScope, prompt: String) {
    _currentSystemPrompt.value = prompt
    val convId = currentConversationId ?: return
    scope.launch(Dispatchers.IO) {
      try {
        val conv = chatRepository.getConversationById(convId) ?: return@launch
        chatRepository.updateConversation(conv.copy(systemPrompt = prompt))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to persist system prompt", e)
      }
    }
  }

  suspend fun getConversationById(conversationId: String): Conversation? =
    chatRepository.getConversationById(conversationId)

  /** Set the current conversation ID for continuing an existing conversation. */
  fun setCurrentConversationId(conversationId: String) {
    currentConversationId = conversationId
  }

  /** Look up the most recent conversation for a model (for auto-resume). */
  suspend fun getLatestConversationForModel(modelName: String): Conversation? {
    return try {
      chatRepository.getLatestConversationForModel(modelName)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get latest conversation for model", e)
      null
    }
  }

  /** Load conversation history for continuing a conversation. */
  suspend fun loadConversationHistory(conversationId: String): List<Message>? {
    return try {
      chatRepository.getMessagesSync(conversationId)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load conversation history", e)
      null
    }
  }

  /** Persist a user message to the encrypted database. */
  fun persistUserMessage(scope: CoroutineScope, model: Model, content: String) {
    Log.d(TAG, "Attempting to persist user message for model: ${model.name}")
    scope.launch(Dispatchers.IO) {
      try {
        // Use Mutex to ensure thread-safe conversation creation
        conversationMutex.withLock {
          // Get or create conversation ID
          val convId =
            if (currentConversationId == null) {
              Log.d(TAG, "Creating new conversation for model: ${model.name}")
              val conv =
                chatRepository.createConversation(
                  title = content.take(50),
                  taskType = "llm_chat",
                  modelName = model.name,
                  systemPrompt = _currentSystemPrompt.value,
                )
              currentConversationId = conv.id
              Log.d(TAG, "Created conversation with ID: ${conv.id}")
              conv.id
            } else {
              currentConversationId!!
            }

          // Now save the message with the guaranteed conversation ID
          try {
            chatRepository.saveMessage(
              conversationId = convId,
              role = "user",
              content = SecurityUtils.sanitizePrompt(content),
            )
            Log.d(TAG, "Successfully persisted user message to conversation: $convId")
          } catch (fkException: android.database.sqlite.SQLiteConstraintException) {
            Log.w(
              TAG,
              "Foreign key constraint failed, conversation might not be committed yet. Retrying...",
              fkException,
            )
            // Retry after a short delay to ensure conversation is committed
            delay(100)
            chatRepository.saveMessage(
              conversationId = convId,
              role = "user",
              content = SecurityUtils.sanitizePrompt(content),
            )
            Log.d(TAG, "Successfully persisted user message to conversation on retry: $convId")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to persist user message", e)
      }
    }
  }

  /** Persist an assistant response to the encrypted database. */
  fun persistAssistantMessage(
    scope: CoroutineScope,
    model: Model,
    content: String,
    latencyMs: Long = 0,
  ) {
    Log.d(TAG, "Attempting to persist assistant message for model: ${model.name}")
    scope.launch(Dispatchers.IO) {
      try {
        // Use Mutex to ensure thread-safe message saving
        conversationMutex.withLock {
          val convId = currentConversationId
          if (convId == null) {
            Log.w(TAG, "No conversation ID available for assistant message, skipping persistence")
            return@withLock
          }

          try {
            chatRepository.saveMessage(
              conversationId = convId,
              role = "assistant",
              content = content,
              latencyMs = latencyMs,
            )
            Log.d(TAG, "Successfully persisted assistant message to conversation: $convId")
          } catch (fkException: android.database.sqlite.SQLiteConstraintException) {
            Log.w(
              TAG,
              "Foreign key constraint failed for assistant message, conversation might not be committed yet. Retrying...",
              fkException,
            )
            // Retry after a short delay to ensure conversation is committed
            delay(100)
            chatRepository.saveMessage(
              conversationId = convId,
              role = "assistant",
              content = content,
              latencyMs = latencyMs,
            )
            Log.d(TAG, "Successfully persisted assistant message to conversation on retry: $convId")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to persist assistant message", e)
      }
    }
  }
}
