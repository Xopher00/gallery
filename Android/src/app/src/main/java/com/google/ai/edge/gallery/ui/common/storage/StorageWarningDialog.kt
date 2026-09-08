// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.ui.common.storage

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.humanReadableSize

/** Warns that a download's size exceeds free device storage; offers proceed-anyway or cancel. */
@Composable
fun StorageWarningDialog(
  requiredBytes: Long,
  freeBytes: Long,
  onProceedAnyway: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    title = { Text(stringResource(R.string.storage_warning_title)) },
    text = {
      Text(
        stringResource(
          R.string.storage_warning_content,
          requiredBytes.humanReadableSize(),
          freeBytes.humanReadableSize(),
        )
      )
    },
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = onProceedAnyway) {
        Text(stringResource(R.string.storage_warning_proceed_anyway))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
