// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 *
 * Extracted from `ui/home/SettingsDialog.kt` per
 * `reference/2026-09-05-merge-friction-reduction.md` §2.1 (item 9 / rank 8): Google's file keeps
 * a single call site (`SecuritySettingsSection(context)`), and everything Box-specific about
 * app security settings lives here instead of interleaved in Google's composable.
 */

package com.google.ai.edge.gallery.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.relay.security.AppLockManager
import com.google.ai.edge.gallery.relay.security.OfflineMode

/**
 * Box's security settings section: biometric app lock, screenshot policy, offline mode, and the
 * privacy notice. Call once from `SettingsDialog`.
 */
@Composable
fun SecuritySettingsSection(context: Context) {
  // Box: Biometric lock toggle
  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(
      "Biometric lock",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      "Require biometric authentication to access the app.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val biometricLockEnabled = remember { mutableStateOf(AppLockManager.isBiometricLockEnabled()) }
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        if (biometricLockEnabled.value) "Enabled" else "Disabled",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Switch(
        checked = biometricLockEnabled.value,
        onCheckedChange = {
          AppLockManager.setBiometricLockEnabled(context, it)
          biometricLockEnabled.value = it
        },
      )
    }
  }

  // Box: Allow screenshots toggle
  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(
      "Allow screenshots",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      "Allow the app to appear in screenshots and screen recordings. Allowed by default; turn off to block them.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val screenshotsEnabled = remember {
      mutableStateOf(AppLockManager.isScreenshotsEnabled())
    }
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        if (screenshotsEnabled.value) "Enabled" else "Disabled",
        style = MaterialTheme.typography.bodyMedium,
      )
      Switch(
        checked = screenshotsEnabled.value,
        onCheckedChange = {
          AppLockManager.setScreenshotsEnabled(context, it)
          screenshotsEnabled.value = it
        },
      )
    }
  }

  // Box: Offline mode toggle
  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(
      "Offline mode",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      "Block all network requests. Models must be pre-downloaded.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val offlineEnabled = OfflineMode.isEnabled.collectAsState()
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        if (offlineEnabled.value) "Enabled" else "Disabled",
        style = MaterialTheme.typography.bodyMedium,
      )
      Switch(
        checked = offlineEnabled.value,
        onCheckedChange = {
          OfflineMode.setEnabled(context, it)
        },
      )
    }
  }

  // Box: Privacy notice
  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(
      "Privacy",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      "Box is a privacy-focused fork of Google AI Edge Gallery. " +
        "Chat history stays on this device. " +
        "Biometric authentication protects app access. " +
        "Not affiliated with Google.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
