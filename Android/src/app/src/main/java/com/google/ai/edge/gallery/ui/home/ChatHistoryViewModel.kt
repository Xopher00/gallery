// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.ui.home

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatHistoryViewModel @Inject constructor(
    private val chatSessionRepository: ChatSessionRepository,
) : ViewModel() {

    // Null means "not loaded yet"; an empty list means "loaded and genuinely empty".
    val conversations: StateFlow<List<ChatSessionProto>?> = chatSessionRepository
        .chatSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedMessages = MutableStateFlow<List<ChatMessageProto>>(emptyList())
    val selectedMessages: StateFlow<List<ChatMessageProto>> = _selectedMessages.asStateFlow()

    fun loadMessages(session: ChatSessionProto) {
        // The messages already live inside the session proto — no separate fetch needed.
        _selectedMessages.value = session.messagesList
    }

    fun deleteConversation(session: ChatSessionProto) {
        viewModelScope.launch(Dispatchers.IO) {
            chatSessionRepository.deleteChatSession(session.sessionId)
        }
    }

    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            chatSessionRepository.clearAllChatSessions()
        }
    }

    fun renameConversation(session: ChatSessionProto, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // saveChatSession upserts by sessionId, so saving the same id back with a new
            // title (every other field preserved via toBuilder) is a rename.
            chatSessionRepository.saveChatSession(session.toBuilder().setTitle(trimmed).build())
        }
    }

    private fun senderLabel(message: ChatMessageProto): String =
        if (message.side == ChatSideProto.CHAT_SIDE_USER) "You" else "Assistant"

    fun exportAll(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sessions = chatSessionRepository.getAllChatSessions()
                val sb = StringBuilder()
                sb.appendLine("Box Chat Export")
                sb.appendLine("Exported: ${fmt.format(Date())}")
                sb.appendLine("Total conversations: ${sessions.size}")
                sb.appendLine("=".repeat(72))
                sessions.forEachIndexed { i, session ->
                    sb.appendLine()
                    sb.appendLine("--- Conversation ${i + 1}: \"${session.title}\" ---")
                    if (session.originalModel.isNotEmpty()) sb.appendLine("Model: ${session.originalModel}")
                    sb.appendLine("Date: ${fmt.format(Date(session.timestampMs))}")
                    sb.appendLine()
                    session.messagesList.forEach { msg ->
                        // Proto messages carry no per-message timestamp, so unlike the Room-backed
                        // export there is no "[timestamp]" prefix here.
                        sb.appendLine("${senderLabel(msg)}:")
                        sb.appendLine(msg.content)
                        sb.appendLine()
                    }
                    sb.appendLine("=".repeat(72))
                }
                val fileName = "box_chat_export_${System.currentTimeMillis()}.txt"
                val saved = saveToDownloads(context, fileName, sb.toString())
                withContext(Dispatchers.Main) {
                    if (saved) Toast.makeText(context, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                    else Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun exportConversation(
        context: Context,
        session: ChatSessionProto,
        messages: List<ChatMessageProto>,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder()
                sb.appendLine("Box Chat Export")
                sb.appendLine("Conversation: ${session.title}")
                if (session.originalModel.isNotEmpty()) sb.appendLine("Model: ${session.originalModel}")
                sb.appendLine("Exported: ${fmt.format(Date())}")
                sb.appendLine("=".repeat(72))
                sb.appendLine()
                messages.forEach { msg ->
                    // No per-message timestamp available on ChatMessageProto — dropped rather
                    // than fabricated (see exportAll).
                    sb.appendLine("${senderLabel(msg)}:")
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                val safeName = session.title.replace(Regex("[^a-zA-Z0-9]"), "_").take(30)
                val fileName = "box_${safeName}_${System.currentTimeMillis()}.txt"
                val saved = saveToDownloads(context, fileName, sb.toString())
                withContext(Dispatchers.Main) {
                    if (saved) Toast.makeText(context, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                    else Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveToDownloads(context: Context, fileName: String, content: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    /**
     * Continue a conversation by handing the caller the session id to navigate with. The caller
     * (ChatHistoryScreen) resolves the model from [ChatSessionProto.getOriginalModel] and
     * navigates the chat route with `sessionId=<session_id>`; the chat screen itself re-hydrates
     * messages from the proto session store keyed by that id.
     */
    fun continueChat(session: ChatSessionProto): Pair<String?, String> {
        val modelName = session.originalModel.takeIf { it.isNotEmpty() }
        return Pair(modelName, session.sessionId)
    }
}
