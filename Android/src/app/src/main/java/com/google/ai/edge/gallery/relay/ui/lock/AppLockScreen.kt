/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 *
 * The lock screen shown while `AppLockManager.isUnlocked` is false. This is the missing half of
 * the app lock feature: `AppLockManager.unlock()` previously had zero callers and there was no UI
 * to reach it from, so turning the toggle on would strand the owner in their own app (recovery
 * would mean clearing app data, destroying downloaded models). This screen is that missing route
 * back in.
 *
 * Authenticator policy (owner decision): BIOMETRIC_STRONG or DEVICE_CREDENTIAL, so the device
 * PIN/pattern/password is always a fallback if biometrics fail, change, or are never enrolled.
 * See `BiometricHelper.authenticateForAppUnlock` for the prompt itself.
 */

package com.google.ai.edge.gallery.relay.ui.lock

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.google.ai.edge.gallery.security.AppLockManager
import com.google.ai.edge.gallery.security.BiometricHelper
import com.google.ai.edge.gallery.security.SecurityAuditLog

/**
 * Full-screen, opaque lock gate. While this is composed, none of the app's real content is
 * present in the tree beneath it (see the call site in `MainActivity`), so it is neither visible
 * nor interactive -- not just painted over.
 */
@Composable
fun AppLockScreen(activity: FragmentActivity) {
  val context = LocalContext.current
  val biometricHelper = remember(activity) { BiometricHelper(activity) }
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var checkedAvailability by remember { mutableStateOf(false) }

  fun attemptUnlock() {
    statusMessage = null
    biometricHelper.authenticateForAppUnlock(
      onSuccess = {
        AppLockManager.unlock()
      },
      onFailure = { _, msg ->
        // A single failed match (e.g. wrong finger). The system prompt stays open and lets the
        // user retry on its own; this just surfaces a message under our button too.
        statusMessage = msg?.toString() ?: "Authentication failed. Try again."
      },
      onError = { code, msg ->
        when (code) {
          BiometricPrompt.ERROR_USER_CANCELED,
          BiometricPrompt.ERROR_CANCELED,
          BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
            // User backed out of the prompt (e.g. system back button). Leave the lock screen
            // showing with its own "Unlock" button so they can retry -- no dead end.
            statusMessage = null
          }
          BiometricPrompt.ERROR_LOCKOUT ->
            statusMessage = "Too many attempts. Wait a moment, or use your device PIN/pattern."
          BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
            statusMessage = "Biometric authentication is locked. Use your device PIN/pattern to continue."
          else -> statusMessage = msg.toString()
        }
      },
    )
  }

  // Checked once when the lock screen first appears. If neither a biometric nor a device
  // credential is enrolled, there is no way for this screen to ever succeed -- enforcing the
  // lock would strand the user permanently. Fail open instead: unlock immediately and record
  // that the lock could not be enforced, rather than leave the user with no route in.
  LaunchedEffect(Unit) {
    if (!checkedAvailability) {
      checkedAvailability = true
      when (biometricHelper.canAuthenticateForAppUnlock()) {
        BiometricHelper.BiometricStatus.AVAILABLE -> attemptUnlock()
        BiometricHelper.BiometricStatus.NOT_ENROLLED,
        BiometricHelper.BiometricStatus.NO_HARDWARE,
        BiometricHelper.BiometricStatus.UNAVAILABLE -> {
          SecurityAuditLog.log(context, "APP_LOCK_UNAVAILABLE_FAIL_OPEN")
          AppLockManager.unlock()
        }
      }
    }
  }

  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
          Icons.Rounded.Lock,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(bottom = 16.dp),
        )
        Text("Box is locked", style = MaterialTheme.typography.titleLarge)
        Text(
          "Authenticate to continue",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        if (statusMessage != null) {
          Text(
            statusMessage!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 12.dp),
          )
        }
        Button(onClick = { attemptUnlock() }) { Text("Unlock") }
      }
    }
  }
}
