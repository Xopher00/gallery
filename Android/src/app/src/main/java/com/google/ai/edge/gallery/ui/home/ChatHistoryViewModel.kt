package com.google.ai.edge.gallery.ui.home

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.local.ChatRepository
import com.google.ai.edge.gallery.data.local.entities.Conversation
import com.google.ai.edge.gallery.data.local.entities.Message
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatHistoryViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = chatRepository
        .getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedMessages = MutableStateFlow<List<Message>>(emptyList())
    val selectedMessages: StateFlow<List<Message>> = _selectedMessages.asStateFlow()

    fun loadMessages(conversationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _selectedMessages.value = chatRepository.getMessagesSync(conversationId)
        }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.deleteConversation(conversation)
        }
    }

    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.deleteAllConversations()
        }
    }

    fun renameConversation(conversation: Conversation, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.updateConversation(conversation.copy(title = trimmed))
        }
    }

    fun exportAll(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val conversations = chatRepository.getAllConversationsSync()
                val sb = StringBuilder()
                sb.appendLine("Box Chat Export")
                sb.appendLine("Exported: ${fmt.format(Date())}")
                sb.appendLine("Total conversations: ${conversations.size}")
                sb.appendLine("=".repeat(72))
                conversations.forEachIndexed { i, conv ->
                    sb.appendLine()
                    sb.appendLine("--- Conversation ${i + 1}: \"${conv.title}\" ---")
                    if (conv.modelName.isNotEmpty()) sb.appendLine("Model: ${conv.modelName}")
                    sb.appendLine("Date: ${fmt.format(Date(conv.createdAt))}")
                    sb.appendLine()
                    chatRepository.getMessagesSync(conv.id).forEach { msg ->
                        val sender = if (msg.role == "user") "You" else "Assistant"
                        sb.appendLine("[${fmt.format(Date(msg.timestamp))}] $sender:")
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

    fun exportConversation(context: Context, conversation: Conversation, messages: List<Message>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder()
                sb.appendLine("Box Chat Export")
                sb.appendLine("Conversation: ${conversation.title}")
                if (conversation.modelName.isNotEmpty()) sb.appendLine("Model: ${conversation.modelName}")
                sb.appendLine("Exported: ${fmt.format(Date())}")
                sb.appendLine("=".repeat(72))
                sb.appendLine()
                messages.forEach { msg ->
                    val sender = if (msg.role == "user") "You" else "Assistant"
                    sb.appendLine("[${fmt.format(Date(msg.timestamp))}] $sender:")
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }
                val safeName = conversation.title.replace(Regex("[^a-zA-Z0-9]"), "_").take(30)
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
     * Continue a conversation by loading its messages back into the active chat.
     * Returns the model and messages to be loaded into the chat screen.
     */
    fun continueChat(conversation: Conversation): Pair<Model?, List<ChatMessage>> {
        val messages = runBlocking {
            chatRepository.getMessagesSync(conversation.id)
        }
        val chatMessages = messages.map { message ->
            ChatMessageText(
                content = message.content,
                side = if (message.role == "user") ChatSide.USER else ChatSide.AGENT,
                latencyMs = message.latencyMs.toFloat()
            )
        }
        
        // Create a Model object for the conversation (simplified for continuation)
        val model = if (conversation.modelName.isNotEmpty()) {
            Model(
                name = conversation.modelName,
                url = "", // Not needed for continuation
                configs = emptyList(),
                sizeInBytes = 0,
                downloadFileName = "",
                showRunAgainButton = false,
                imported = true,
                llmSupportImage = false,
                llmSupportAudio = false,
                llmSupportTinyGarden = false,
                llmSupportMobileActions = false,
                llmMaxToken = 0,
                accelerators = emptyList(),
                isLlm = true,
                runtimeType = com.google.ai.edge.gallery.data.RuntimeType.LITERT_LM
            )
        } else null
        
        return Pair(model, chatMessages)
    }
}
