// Copyright 2026 Google LLC. Licensed under the Apache License, Version 2.0.

package com.google.ai.edge.gallery.customtasks.agentchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.tools.PermissionResult

/** A dialog that prompts the user for permission to execute an in-app local tool call. */
@Composable
fun LocalToolCallPermissionDialog(
  toolName: String,
  argument: String,
  onResult: (PermissionResult) -> Unit,
) {
  AlertDialog(
    onDismissRequest = { onResult(PermissionResult.DENY) },
    title = {
      Text(
        stringResource(R.string.local_tool_call_permission_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 22.sp, stepSize = 1.sp),
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.local_tool_name_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(text = toolName, style = MaterialTheme.typography.bodySmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.local_tool_summary_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(text = argument, style = MaterialTheme.typography.bodySmall)
        }
      }
    },
    confirmButton = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
          onClick = { onResult(PermissionResult.ALWAYS_ALLOW) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.mcp_tool_always_allow))
        }
        Button(
          onClick = { onResult(PermissionResult.ALLOW_ONCE) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.mcp_tool_allow_once))
        }
        OutlinedButton(
          onClick = { onResult(PermissionResult.DENY) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.mcp_tool_dont_allow))
        }
      }
    },
  )
}
