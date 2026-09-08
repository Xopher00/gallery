// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.ui.common.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Document-attach feature for MessageInputText: pick a small text-like document, hold it as a
// pending attachment (shown as a chip next to the image/audio previews), and merge its content
// into the outgoing message as a fenced block at send time. Everything the feature needs --
// picker launcher, pending-document state, the chip, the "Attach document" menu entry, and the
// send-time merge -- lives in this file so MessageInputText.kt only needs to hold and pass along
// a single state object.

/** Holds the document-attach feature's state: the currently picked (name, content) pair, if any. */
class DocumentAttachmentState internal constructor(private val launchPickerIntent: (Intent) -> Unit) {
  var pending: Pair<String, String>? by mutableStateOf(null)
    internal set

  fun clear() {
    pending = null
  }

  fun launchPicker() {
    val intent =
      Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(
          Intent.EXTRA_MIME_TYPES,
          arrayOf(
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/json",
            "text/xml",
            "text/html",
            "text/x-python",
            "text/javascript",
            "text/x-java-source",
            "text/x-kotlin",
          ),
        )
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
      }
    launchPickerIntent(intent)
  }

  /** Returns [baseMessage] with the pending document (if any) appended as a fenced block. */
  fun mergeIntoMessage(baseMessage: String): String {
    val doc = pending ?: return baseMessage
    val docBlock = "\n\n---\n📄 **Attached Document: ${doc.first}**\n```\n${doc.second}\n```\n---\n"
    return if (baseMessage.isEmpty()) docBlock.trimStart('\n') else baseMessage + docBlock
  }
}

@Composable
fun rememberDocumentAttachmentState(
  context: Context,
  scope: CoroutineScope,
): DocumentAttachmentState {
  lateinit var state: DocumentAttachmentState
  val launcher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        result.data?.data?.let { uri ->
          scope.launch(Dispatchers.IO) {
            val doc = try { readDocumentContent(context, uri) } catch (e: Exception) { null }
            withContext(Dispatchers.Main) { state.pending = doc }
          }
        }
      }
    }
  state = remember { DocumentAttachmentState(launchPickerIntent = { intent -> launcher.launch(intent) }) }
  return state
}

/** The attached-document chip shown alongside the image/audio previews. No-op if none pending. */
@Composable
fun DocumentAttachmentChip(state: DocumentAttachmentState) {
  val doc = state.pending ?: return
  Box(contentAlignment = Alignment.TopEnd) {
    Row(
      modifier =
        Modifier.shadow(2.dp, RoundedCornerShape(8.dp))
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surface)
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
          .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        Icons.Rounded.AttachFile,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Text(
        doc.first,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    MediaPanelCloseButton { state.clear() }
  }
}

/** The "Attach document" entry in the add-content dropdown menu. Emits nothing if [show] is false. */
@Composable
fun DocumentAttachmentMenuItem(
  show: Boolean,
  state: DocumentAttachmentState,
  onDismissMenu: () -> Unit,
) {
  if (!show) return
  DropdownMenuItem(
    text = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(Icons.Rounded.AttachFile, contentDescription = null)
        Text("Attach document")
      }
    },
    onClick = {
      onDismissMenu()
      state.launchPicker()
    },
  )
}

private fun readDocumentContent(context: Context, uri: Uri): Pair<String, String>? {
  val filename = resolveDocumentFilename(context, uri)
  val content = context.contentResolver.openInputStream(uri)?.use { stream ->
    val text = stream.bufferedReader(Charsets.UTF_8).readText()
    if (text.length > 50_000) text.take(50_000) + "\n\n[Content truncated due to length]" else text
  } ?: return null
  return Pair(filename, content)
}

private fun resolveDocumentFilename(context: Context, uri: Uri): String {
  context.contentResolver.query(
    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
  )?.use { cursor ->
    if (cursor.moveToFirst()) {
      val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (idx >= 0) return cursor.getString(idx)
    }
  }
  return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
}
