/*
 * Fork-owned file. Not part of upstream google-ai-edge/gallery.
 *
 * Extracted from `ui/home/HomeScreen.kt` per
 * `reference/2026-09-05-merge-friction-reduction.md` §2.1 (item 8 / rank 6): the API-Server
 * drawer tile and the new-chat/history/import quick-action row are Box additions that were
 * interleaved directly into Google's composable. Google's file keeps two small call sites;
 * everything about what these actions look like lives here instead.
 */

package com.google.ai.edge.gallery.relay.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.home.SquareDrawerItem
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Box's "API Server" drawer tile, placed below Google's Settings/Models row. `closeDrawer` lets
 * the caller close the drawer immediately (matching Google's own drawer items) while
 * `onServerClicked` fires after the same short delay Google uses elsewhere in this drawer.
 */
@Composable
fun HomeApiServerDrawerItem(
  scope: CoroutineScope,
  onServerClicked: () -> Unit,
  closeDrawer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier = modifier.fillMaxWidth()) {
    SquareDrawerItem(
      label = "API Server",
      description = "Local OpenAI-compatible API server",
      icon = Icons.Rounded.Dns,
      onClick = {
        closeDrawer()
        scope.launch {
          delay(50)
          onServerClicked()
        }
      },
      modifier = Modifier.weight(1f),
      iconBrush =
        linearGradient(
          colors =
            listOf(
              MaterialTheme.customColors.taskBgGradientColors[0][0],
              MaterialTheme.customColors.taskBgGradientColors[0][1],
            )
        ),
    )
  }
}

/**
 * Box's quick-action row (New Chat / History / Import) shown under the home screen's intro text.
 */
@Composable
fun HomeQuickActionsRow(
  onNewChatClicked: () -> Unit,
  navigateToChatHistory: () -> Unit,
  onImportModelClicked: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .height(IntrinsicSize.Min)
        .padding(horizontal = 24.dp)
        .padding(bottom = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FilledTonalButton(onClick = onNewChatClicked, modifier = Modifier.weight(1f).fillMaxHeight()) {
      Icon(Icons.Rounded.AddComment, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(4.dp))
      Text("New Chat", maxLines = 1)
    }
    FilledTonalButton(
      onClick = navigateToChatHistory,
      modifier = Modifier.weight(1f).fillMaxHeight(),
    ) {
      Icon(Icons.Rounded.Forum, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(4.dp))
      Text("History", maxLines = 1)
    }
    FilledTonalButton(
      onClick = onImportModelClicked,
      modifier = Modifier.weight(1f).fillMaxHeight(),
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
      Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(4.dp))
      Column {
        Text("Import", maxLines = 1)
        Text(
          "GGUF · LiteRT",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
