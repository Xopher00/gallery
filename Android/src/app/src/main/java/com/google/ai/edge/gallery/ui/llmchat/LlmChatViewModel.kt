/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.agent.AgentEvent
import com.google.ai.edge.gallery.agent.AgentExecutionContext
import com.google.ai.edge.gallery.agent.AgentRequest
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.agent.Attachment
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.common.SystemPromptHelper
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.awaitInitialization
import com.google.ai.edge.gallery.data.local.ChatRepository
import com.google.ai.edge.gallery.security.SecurityUtils
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.chat.convertToLitertMessage
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGLlmChatViewModel"

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase(
  private val systemPromptRepository: SystemPromptRepository? = null,
  chatSessionRepository: ChatSessionRepository? = null,
  private val modelFeedbackRepository: Any? = null,
  override val runtimeExecutor: AgentRuntimeExecutor,
  override val llmSessionManager: LlmSessionManager? = null,
  val chatRepository: ChatRepository,
) : ChatViewModel(chatSessionRepository, runtimeExecutor, llmSessionManager) {
  private val _uiSystemPrompt = MutableStateFlow("")
  val uiSystemPrompt = _uiSystemPrompt.asStateFlow()
  // Map to track if the session was stopped by the model for a given model name.
  private val sessionStoppedByModel = mutableMapOf<String, Boolean>()
  // The current task ID for the session.
  private var currentTaskId: String = ""

  // Box: Chat persistence state.
  private var currentConversationId: String? = null
  private val conversationMutex = Mutex()

  private val _currentSystemPrompt = MutableStateFlow("")
  val currentSystemPrompt: StateFlow<String> = _currentSystemPrompt.asStateFlow()

  fun setCurrentSystemPrompt(prompt: String) {
    _currentSystemPrompt.value = prompt
  }

  fun updateSystemPrompt(prompt: String) {
    _currentSystemPrompt.value = prompt
    val convId = currentConversationId ?: return
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val conv = chatRepository.getConversationById(convId) ?: return@launch
        chatRepository.updateConversation(conv.copy(systemPrompt = prompt))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to persist system prompt", e)
      }
    }
  }

  suspend fun getConversationById(conversationId: String) =
    chatRepository.getConversationById(conversationId)

  /**
   * Box: Set the current conversation ID for continuing an existing conversation
   */
  fun setCurrentConversationId(conversationId: String) {
    currentConversationId = conversationId
  }

  /**
   * Box: Look up the most recent conversation for a model (for auto-resume).
   */
  suspend fun getLatestConversationForModel(modelName: String): com.google.ai.edge.gallery.data.local.entities.Conversation? {
    return try {
      chatRepository.getLatestConversationForModel(modelName)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get latest conversation for model", e)
      null
    }
  }

  /**
   * Sets the system prompt in the UI.
   *
   * This method updates the UI system prompt without saving it to the repository or resetting the
   * session. It is primarily used for initializing the UI system prompt.
   *
   * @param systemPrompt The new system prompt to set in the UI.
   */
  fun setUISystemPrompt(systemPrompt: String) {
    _uiSystemPrompt.value = systemPrompt
  }

  /**
   * Loads the system prompt for the given [task] from the repository.
   *
   * @param task The task to load the system prompt for.
   */
  fun loadSystemPrompt(task: Task) {
    currentTaskId = task.id
    viewModelScope.launch {
      val effectivePrompt =
        SystemPromptHelper.getEffectiveSystemPrompt(systemPromptRepository, task)
      _uiSystemPrompt.value = effectivePrompt
    }
  }

  /**
   * Applies a system prompt change to the given [task] and [model].
   *
   * This method updates the UI system prompt, saves the new prompt to the repository, and resets
   * the session with the new prompt.
   *
   * @param task The task to apply the system prompt change to.
   * @param model The model to apply the system prompt change to.
   * @param newPrompt The new system prompt to apply.
   * @param systemPromptUpdatedMessage The message to add to the chat after the system prompt is
   *   updated.
   */
  fun applySystemPromptChange(
    task: Task,
    model: Model,
    newPrompt: String,
    systemPromptUpdatedMessage: String,
  ) {
    _uiSystemPrompt.value = newPrompt
    viewModelScope.launch {
      systemPromptRepository?.updateSystemPrompt(task.id, newPrompt)
      resetSession(
        task = task,
        model = model,
        systemInstruction = newPrompt,
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        onDone = { addMessage(model, ChatMessageInfo(content = systemPromptUpdatedMessage)) },
      )
    }
  }

  /**
   * Box: Load conversation history for continuing a conversation
   */
  suspend fun loadConversationHistory(conversationId: String): List<com.google.ai.edge.gallery.data.local.entities.Message>? {
    return try {
      chatRepository.getMessagesSync(conversationId)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load conversation history", e)
      null
    }
  }

  /**
   * Box: Persist a user message to the encrypted database.
   */
  private fun persistUserMessage(model: Model, content: String) {
    Log.d(TAG, "Attempting to persist user message for model: ${model.name}")
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Use Mutex to ensure thread-safe conversation creation
        conversationMutex.withLock {
          // Get or create conversation ID
          val convId = if (currentConversationId == null) {
            Log.d(TAG, "Creating new conversation for model: ${model.name}")
            val conv = chatRepository.createConversation(
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
            Log.w(TAG, "Foreign key constraint failed, conversation might not be committed yet. Retrying...", fkException)
            // Retry after a short delay to ensure conversation is committed
            kotlinx.coroutines.delay(100)
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

  /**
   * Box: Persist an assistant response to the encrypted database.
   */
  private fun persistAssistantMessage(model: Model, content: String, latencyMs: Long = 0) {
    Log.d(TAG, "Attempting to persist assistant message for model: ${model.name}")
    viewModelScope.launch(Dispatchers.IO) {
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
            Log.w(TAG, "Foreign key constraint failed for assistant message, conversation might not be committed yet. Retrying...", fkException)
            // Retry after a short delay to ensure conversation is committed
            kotlinx.coroutines.delay(100)
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

  open fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // Box: Persist user message to encrypted DB
      if (input.isNotEmpty()) {
        persistUserMessage(model, input)
      }

      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      val attachments = mutableListOf<Attachment>()
      for (image in images) {
        attachments.add(Attachment.ImageBitmap(image))
      }
      for (audioMessage in audioMessages) {
        attachments.add(Attachment.AudioBytes(audioMessage.genByteArrayForWav()))
      }

      val enableThinking =
        allowThinking &&
          model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
      val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else emptyMap()
      val metadata =
        buildMap<String, Any> {
          put(AgentRequest.SESSION_ID, currentSessionId)
          if (extraContext.isNotEmpty()) {
            put(AgentRequest.LITERTLM_EXTRA_CONTEXT, extraContext)
          }
        }

      val request = AgentRequest(query = input, attachments = attachments, metadata = metadata)

      val context = AgentExecutionContext()

      var firstRun = true
      val start = System.currentTimeMillis()

      if (sessionStoppedByModel[model.name] == true) {
        sessionStoppedByModel[model.name] = false
        val initialMessages =
          (uiState.value.messagesByModel[model.name] ?: emptyList())
            .filterIsInstance<ChatMessageText>()
            .dropLast(1)
            .mapNotNull { convertToLitertMessage(it) }
        val config =
          AgentRuntimeConfig(
            model = model,
            taskId = currentTaskId,
            supportImage = model.llmSupportImage,
            supportAudio = model.llmSupportAudio,
            systemInstruction = _uiSystemPrompt.value.ifEmpty { null },
            initialMessages = initialMessages,
          )
        runtimeExecutor.resetSession(config = config)
      }

      // Run inference.
      runtimeExecutor.executeStream(context = context, request = request).collect { event ->
        when (event) {
          is AgentEvent.LoopInitiated -> {}
          is AgentEvent.StreamToken -> {
            val lastMessage = getLastMessage(model = model)
            val wasLoading = lastMessage?.type == ChatMessageType.LOADING
            // Remove the last message if it is a "loading" message.
            // This will only be done once.
            if (wasLoading) {
              removeLastMessage(model = model)
            }

            val thinkingText = event.thinking
            val isThinking = !thinkingText.isNullOrEmpty()
            var currentLastMessage = getLastMessage(model = model)

            // If thinking is enabled, add a thinking message.
            if (isThinking) {
              if (currentLastMessage?.type != ChatMessageType.THINKING) {
                addMessage(
                  model = model,
                  message =
                    ChatMessageThinking(
                      content = "",
                      inProgress = true,
                      side = ChatSide.AGENT,
                      accelerator = accelerator,
                      hideSenderLabel =
                        currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                    ),
                )
              }
              updateLastThinkingMessageContentIncrementally(
                model = model,
                partialContent = thinkingText!!,
              )
            } else {
              if (currentLastMessage?.type == ChatMessageType.THINKING) {
                val thinkingMsg = currentLastMessage as ChatMessageThinking
                if (thinkingMsg.inProgress) {
                  replaceLastMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = thinkingMsg.content,
                        inProgress = false,
                        side = thinkingMsg.side,
                        accelerator = thinkingMsg.accelerator,
                        hideSenderLabel = thinkingMsg.hideSenderLabel,
                      ),
                    type = ChatMessageType.THINKING,
                  )
                }
              }
              currentLastMessage = getLastMessage(model = model)
              if (
                currentLastMessage?.type != ChatMessageType.TEXT ||
                  currentLastMessage.side != ChatSide.AGENT
              ) {
                // Add an empty message that will receive streaming results.
                addMessage(
                  model = model,
                  message =
                    ChatMessageText(
                      content = "",
                      side = ChatSide.AGENT,
                      accelerator = accelerator,
                      hideSenderLabel =
                        currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                          currentLastMessage?.type == ChatMessageType.THINKING,
                    ),
                )
              }

              // Incrementally update the streamed partial results.
              val latencyMs: Long = if (event.done) System.currentTimeMillis() - start else -1
              if (event.token.isNotEmpty() || wasLoading || event.done) {
                updateLastTextMessageContentIncrementally(
                  model = model,
                  partialContent = event.token,
                  latencyMs = latencyMs.toFloat(),
                )
              }
            }

            if (firstRun) {
              firstRun = false
              setPreparing(false)
              onFirstToken(model)
            }
          }
          is AgentEvent.LoopTerminated -> {
            val finalLastMessage = getLastMessage(model = model)
            if (finalLastMessage?.type == ChatMessageType.THINKING) {
              val thinkingMsg = finalLastMessage as ChatMessageThinking
              if (thinkingMsg.inProgress) {
                replaceLastMessage(
                  model = model,
                  message =
                    ChatMessageThinking(
                      content = thinkingMsg.content,
                      inProgress = false,
                      side = thinkingMsg.side,
                      accelerator = thinkingMsg.accelerator,
                      hideSenderLabel = thinkingMsg.hideSenderLabel,
                    ),
                  type = ChatMessageType.THINKING,
                )
              }
            }
            setInProgress(false)
            setPreparing(false)
            onDone()

            // Box: Persist assistant response to encrypted DB
            val assistantMsg = getLastMessageWithTypeAndSide(model, ChatMessageType.TEXT, ChatSide.AGENT)
            if (assistantMsg is ChatMessageText && assistantMsg.content.isNotEmpty()) {
              persistAssistantMessage(model, assistantMsg.content, assistantMsg.latencyMs.toLong())
            }
          }
          is AgentEvent.Error -> {
            Log.e(TAG, "Error occurred while running inference: ${event.errorMessage}")
            setInProgress(false)
            setPreparing(false)
            onError(event.errorMessage)
          }
          is AgentEvent.LoopCancelled -> {
            setInProgress(false)
            setPreparing(false)
          }
        }
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    } else {
      sessionStoppedByModel[model.name] = true
    }
    setInProgress(false)
    runtimeExecutor.interrupt()
    Log.d(TAG, "Done stopping response")
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: String? = null,
    actionChannel: SendChannel<ToolAction>? = null,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message> = listOf(),
    clearHistory: Boolean = true,
  ) {
    currentTaskId = task.id
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      if (clearHistory) {
        currentSessionId = UUID.randomUUID().toString()
        clearAllMessages(model = model)
      }
      stopResponse(model = model)
      sessionStoppedByModel[model.name] = false

      val config =
        AgentRuntimeConfig(
          sessionId = currentSessionId,
          model = model,
          taskId = task.id,
          actionChannel = actionChannel,
          supportImage = supportImage,
          supportAudio = supportAudio,
          enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
          systemInstruction = systemInstruction,
          initialMessages = initialMessages,
        )
      runtimeExecutor.resetSession(config = config)

      setIsResettingSession(false)
      onDone()
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      if (model.instance == null) {
        try {
          model.awaitInitialization()
        } catch (e: Exception) {
          onError("Model initialization failed: ${e.message}")
          return@launch
        }
      }
      if (model.instance == null) {
        onError("Model not initialized.")
        return@launch
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(
            context = context,
            task = task,
            model = model,
            onDone = {
              // Add a warning message for re-initializing the session.
              addMessage(
                model = model,
                message = ChatMessageWarning(content = "Session re-initialized"),
              )
            },
            onError = {
              addMessage(
                model = model,
                message =
                  ChatMessageError(
                    content = "Failed to re-initialize session, please restart the app"
                  ),
              )
            },
          )
        },
      )
    }
  }
}

@HiltViewModel
open class LlmChatViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  chatSessionRepository: ChatSessionRepository,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  llmSessionManager: LlmSessionManager,
  chatRepository: ChatRepository,
) :
LlmChatViewModelBase(systemPromptRepository, chatSessionRepository, null, runtimeExecutor,
llmSessionManager, chatRepository)

@HiltViewModel
class LlmAskImageViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  chatSessionRepository: ChatSessionRepository,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  llmSessionManager: LlmSessionManager,
  chatRepository: ChatRepository,
) :
LlmChatViewModelBase(systemPromptRepository, chatSessionRepository, null, runtimeExecutor,
llmSessionManager, chatRepository)

@HiltViewModel
class LlmAskAudioViewModel
@Inject
constructor(
  systemPromptRepository: SystemPromptRepository,
  chatSessionRepository: ChatSessionRepository,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  llmSessionManager: LlmSessionManager,
  chatRepository: ChatRepository,
) :
LlmChatViewModelBase(systemPromptRepository, chatSessionRepository, null, runtimeExecutor,
llmSessionManager, chatRepository)
