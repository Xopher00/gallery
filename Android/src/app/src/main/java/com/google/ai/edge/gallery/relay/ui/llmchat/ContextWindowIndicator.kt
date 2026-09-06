/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 *
 * Extracted from LlmChatScreen.kt so that Google's file carries only a call
 * line into this composable instead of the full implementation. Keeping relay
 * behavior in relay files behind a one-line seam is what keeps upstream merges
 * clean; see reference/2026-09-05-merge-friction-reduction.md.
 */

package com.google.ai.edge.gallery.relay.ui.llmchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase

/** Shows an estimated fraction of the model's context window used by the current conversation. */
@Composable
fun ContextWindowIndicator(model: Model, viewModel: LlmChatViewModelBase) {
  val uiState by viewModel.uiState.collectAsState()
  val messages = uiState.messagesByModel[model.name] ?: return

  val totalChars = messages
    .filter { it.type == ChatMessageType.TEXT }
    .sumOf { (it as? ChatMessageText)?.content?.length ?: 0 }

  val maxTokens = model.configValues[ConfigKeys.MAX_TOKENS.label]
    ?.toString()?.toIntOrNull()?.takeIf { it > 0 }
    ?: model.llmMaxToken.takeIf { it > 0 }
    ?: 1024
  val estimatedTokens = (totalChars / 4).coerceAtMost(maxTokens)
  val fraction = (estimatedTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
  if (estimatedTokens == 0) return

  val color = when {
    fraction > 0.85f -> MaterialTheme.colorScheme.error
    fraction > 0.65f -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
  }

  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
  ) {
    LinearProgressIndicator(
      progress = { fraction },
      modifier = Modifier.fillMaxWidth(),
      color = color,
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        "Context",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
      )
      Text(
        "~$estimatedTokens / $maxTokens tokens",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
      )
    }
  }
}
