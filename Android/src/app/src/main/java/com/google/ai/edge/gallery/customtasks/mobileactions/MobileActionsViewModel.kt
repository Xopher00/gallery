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
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGMAViewModel"

/** The UI state of the MobileActionsViewModel. */
data class MobileActionsUiState(
  val showWelcomeMessage: Boolean = true,
  val processing: Boolean = false,
  val userPrompt: String = "",
  val modelResponse: String = "",
  val functionCallDetails: List<String> = listOf(),
  val noFunctionRecognized: Boolean = false,
)

@HiltViewModel
class MobileActionsViewModel
@Inject
constructor(@ApplicationContext private val appContext: Context) : ViewModel() {
  protected val _uiState = MutableStateFlow(MobileActionsUiState())
  val uiState = _uiState.asStateFlow()

  private val _isResettingConversation = MutableStateFlow(false)
  private val isResettingConversation = _isResettingConversation.asStateFlow()

  fun reset() {
    val unused = MobileActionExecutor.setFlashlight(context = appContext, isEnabled = false)
    setShowWelcomeMessage(showWelcomeMessage = true)
    setUserPrompt(prompt = "'")
    setModelResponse(response = "")
    setNoFunctionRecognized(value = false)
    clearFunctionCallDetails()
  }

  fun cleanUp() {
    val unused = MobileActionExecutor.setFlashlight(context = appContext, isEnabled = false)
  }

  fun setShowWelcomeMessage(showWelcomeMessage: Boolean) {
    _uiState.update { _uiState.value.copy(showWelcomeMessage = showWelcomeMessage) }
  }

  fun setProcessing(processing: Boolean) {
    _uiState.update { _uiState.value.copy(processing = processing) }
  }

  fun setUserPrompt(prompt: String) {
    _uiState.update { _uiState.value.copy(userPrompt = prompt) }
  }

  fun setModelResponse(response: String) {
    _uiState.update { _uiState.value.copy(modelResponse = response) }
  }

  fun appendModelResponse(partialResponse: String) {
    _uiState.update {
      _uiState.value.copy(modelResponse = _uiState.value.modelResponse + partialResponse)
    }
  }

  fun addFunctionCallDetails(details: String) {
    val newDetails = _uiState.value.functionCallDetails.toMutableList()
    newDetails.add(details)
    _uiState.update { _uiState.value.copy(functionCallDetails = newDetails) }
  }

  fun clearFunctionCallDetails() {
    _uiState.update { _uiState.value.copy(functionCallDetails = listOf()) }
  }

  fun setNoFunctionRecognized(value: Boolean) {
    _uiState.update { _uiState.value.copy(noFunctionRecognized = value) }
  }

  fun processUserPrompt(
    model: Model,
    userPrompt: String,
    tools: List<ToolProvider>,
    onProcessDone: () -> Unit,
    onError: (error: String) -> Unit,
  ) {
    if (model.instance == null) {
      setProcessing(processing = false)
      return
    }

    viewModelScope.launch(Dispatchers.Default) {
      Log.d(TAG, "Start processing user prompt: $userPrompt")
      setProcessing(processing = true)
      setShowWelcomeMessage(showWelcomeMessage = false)

      // Clean up.
      setModelResponse(response = "")
      setNoFunctionRecognized(value = false)
      clearFunctionCallDetails()

      // Set user prompt.
      setUserPrompt(prompt = userPrompt)

      // Wait until the conversation is NOT resetting.
      Log.d(TAG, "Waiting for any ongoing conversation reset to be done...")
      isResettingConversation.first { !it }
      Log.d(TAG, "Done waiting. Start inference.")

      // Run inference.
      val instance = model.instance as LlmModelInstance
      val conversation = instance.conversation
      val contents = mutableListOf<Content>()
      if (userPrompt.trim().isNotEmpty()) {
        contents.add(Content.Text(userPrompt))
      }

      conversation
        .sendMessageAsync(Contents.of(contents))
        .catch {
          Log.e(TAG, "Failed to run inference", it)
          onError(it.message ?: "Unknown error")
        }
        .onCompletion {
          setProcessing(processing = false)
          onProcessDone()
          resetConversation(model = model, tools = tools)
        }
        .collect {
          setProcessing(processing = false)
          appendModelResponse(partialResponse = it.toString())
        }
    }
  }

  fun resetConversation(model: Model, tools: List<ToolProvider>) {
    _isResettingConversation.value = true
    LlmChatModelHelper.resetConversation(
      model = model,
      supportImage = false,
      supportAudio = false,
      systemInstruction = getSystemPrompt(),
      tools = tools,
    )
    _isResettingConversation.value = false
  }

  fun resetEngine(
    context: Context,
    model: Model,
    tools: List<ToolProvider>,
    modelManagerViewModel: ModelManagerViewModel,
    onError: (error: String) -> Unit,
  ) {
    reset()

    viewModelScope.launch(Dispatchers.Default) {
      model.resetInitialization()
      LlmChatModelHelper.cleanUp(
        model = model,
        onDone = {
          LlmChatModelHelper.initialize(
            context = context,
            model = model,
            taskId = BuiltInTaskId.LLM_MOBILE_ACTIONS,
            supportImage = false,
            supportAudio = false,
            onDone = { error ->
              if (error.isNotEmpty()) {
                model.markInitializationFailed(error)
                onError(error)
              } else {
                model.markInitialized()
              }
            },
            systemInstruction = getSystemPrompt(),
            tools = tools,
          )
        },
      )
    }
  }

  fun performAction(action: Action, context: Context): String =
    MobileActionExecutor.performAction(action, context)
}
