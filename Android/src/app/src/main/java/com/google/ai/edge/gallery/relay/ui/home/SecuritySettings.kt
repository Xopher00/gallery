/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 *
 * Extracted from `ui/home/SettingsDialog.kt` per
 * `reference/2026-09-05-merge-friction-reduction.md` §2.1 (item 9 / rank 8): Google's file keeps
 * a single call site (`SecuritySettingsSection(context)`), and everything Box-specific about
 * app security settings lives here instead of interleaved in Google's composable.
 */

package com.google.ai.edge.gallery.relay.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.google.ai.edge.gallery.security.AppLockManager
import com.google.ai.edge.gallery.security.BiometricEncryptionManager
import com.google.ai.edge.gallery.security.OfflineMode
import com.google.ai.edge.gallery.security.PassphraseHolder
import com.google.ai.edge.gallery.security.SecurityUtils

/**
 * Box's security settings section: biometric app lock, screenshot policy, biometric database
 * encryption, offline mode, and the privacy notice. Call once from `SettingsDialog`.
 */
@Composable
fun SecuritySettingsSection(context: Context) {
  // Box: Biometric lock toggle
  val dbEncEnabled by BiometricEncryptionManager.isEnabledFlow.collectAsState()
  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(
      "Biometric lock",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      if (dbEncEnabled) "Covered by database encryption — disable that first to use this."
      else "Require biometric authentication to access the app.",
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
        color = if (dbEncEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.onSurface,
      )
      Switch(
        checked = biometricLockEnabled.value,
        enabled = !dbEncEnabled,
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
      "Allow the app to appear in screenshots and screen recordings. Off by default for privacy.",
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

  // Box: Biometric database encryption toggle
  BiometricEncryptionSection(context)

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
        "Chat history is encrypted with SQLCipher. " +
        "Biometric authentication protects app access. " +
        "Not affiliated with Google.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun BiometricEncryptionSection(context: Context) {
    // LocalContext inside a Compose Dialog is a ContextThemeWrapper, not FragmentActivity directly.
    // Unwrap the chain to find the real activity.
    val activity = remember(context) {
        var ctx: android.content.Context = context
        while (ctx is android.content.ContextWrapper && ctx !is FragmentActivity) {
            ctx = ctx.baseContext
        }
        ctx as? FragmentActivity
    }
    var isEnabled by remember { mutableStateOf(BiometricEncryptionManager.isEnabled(context)) }
    var hardwareLevel by remember {
        mutableStateOf(if (isEnabled) BiometricEncryptionManager.getHardwareLevel() else "")
    }
    var showEnableDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(
            "Biometric database encryption",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        Text(
            "Protect the database key with biometrics. If your biometrics change, you may lose access to chat history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isEnabled && hardwareLevel.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (hardwareLevel == "StrongBox") Icons.Rounded.Security else Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    "Protected by $hardwareLevel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (statusText.isNotEmpty()) {
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isEnabled) "Enabled" else "Disabled",
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = isEnabled,
                onCheckedChange = {
                    statusText = ""
                    if (it) showEnableDialog = true else showDisableDialog = true
                },
            )
        }
    }

    if (showEnableDialog) {
        AlertDialog(
            onDismissRequest = { showEnableDialog = false },
            title = { Text("Enable biometric encryption?") },
            text = {
                Text(
                    "Your database key will be encrypted with your biometrics. " +
                    "If you change or remove your biometrics, you will lose access to your chat history.\n\n" +
                    "Biometric lock will be disabled automatically (database encryption already protects app access).\n\n" +
                    "Export important chats before enabling."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEnableDialog = false
                    if (activity == null) return@TextButton
                    BiometricEncryptionManager.promptEncrypt(
                        activity = activity,
                        onSuccess = { cipher ->
                            val plain = SecurityUtils.getOrCreatePlainPassphrase(context)
                            BiometricEncryptionManager.storeEncryptedPassphrase(context, cipher, plain)
                            SecurityUtils.clearPlainPassphrase(context)
                            PassphraseHolder.set(plain)
                            isEnabled = true
                            hardwareLevel = BiometricEncryptionManager.getHardwareLevel()
                            // Biometric lock is redundant when DB encryption is active — disable it.
                            AppLockManager.setBiometricLockEnabled(context, false)
                        },
                        onFailure = { _, _ -> statusText = "Authentication failed" },
                        onError = { _, msg -> statusText = msg.toString() },
                    )
                }) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            title = { Text("Disable biometric encryption?") },
            text = { Text("Authenticate to confirm. The database key will be stored without biometric protection.") },
            confirmButton = {
                TextButton(onClick = {
                    showDisableDialog = false
                    if (activity == null) return@TextButton
                    BiometricEncryptionManager.promptDecrypt(
                        activity = activity,
                        context = context,
                        onSuccess = { cipher ->
                            val plain = BiometricEncryptionManager.decryptPassphrase(context, cipher)
                            SecurityUtils.storePlainPassphrase(context, plain)
                            BiometricEncryptionManager.disable(context)
                            PassphraseHolder.clear()
                            isEnabled = false
                            hardwareLevel = ""
                        },
                        onFailure = { _, _ -> statusText = "Authentication failed" },
                        onError = { _, msg -> statusText = msg.toString() },
                    )
                }) {
                    Text("Disable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) { Text("Cancel") }
            },
        )
    }
}
