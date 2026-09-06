/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 *
 * The missing decrypt-at-startup screen for biometric database encryption (see
 * `SecurityUtils.getDatabasePassphrase()` and `PassphraseHolder`). The passphrase lives in
 * `PassphraseHolder` in memory only and dies with the process; nothing previously prompted to put
 * it back after a restart, so the first process death after enabling encryption made
 * `BoxChatDatabase.getInstance()` throw `IllegalStateException` the moment anything touched the
 * database -- with no route back in short of clearing app data (which also destroys downloaded
 * models). This screen is that missing route back in, built the same shape as `AppLockScreen`
 * (see that file's header) so there is exactly one gate pattern in this codebase, not two.
 *
 * Unlike `AppLockScreen`, this gate never fails open. The app lock has a plain
 * BIOMETRIC_STRONG-or-DEVICE_CREDENTIAL check with nothing behind it to protect, so when neither
 * is available it is safe to just unlock. Here the "lock" is a real encryption key: the SQLCipher
 * passphrase is stored only in a form only the Keystore-bound biometric key can decrypt (see
 * `BiometricEncryptionManager`). If biometric auth cannot succeed, the passphrase cannot be
 * recovered, full stop -- so this screen stays up and keeps offering Retry (or, for a permanently
 * invalidated key, explains that there is nothing to retry) rather than ever calling
 * `PassphraseHolder.set(...)` with nothing to set.
 */

package com.google.ai.edge.gallery.relay.ui.lock

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockClock
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
import android.util.Log
import com.google.ai.edge.gallery.security.BiometricEncryptionManager
import com.google.ai.edge.gallery.security.BiometricHelper
import com.google.ai.edge.gallery.security.PassphraseHolder
import com.google.ai.edge.gallery.security.SecurityAuditLog

private const val TAG = "AGDatabaseUnlock"

/**
 * Full-screen, opaque gate shown when biometric database encryption is enabled but the
 * passphrase is not (yet, or no longer) held in [PassphraseHolder] -- i.e. every process start
 * with encryption on, until this succeeds. While this is composed, none of the app's real content
 * (and therefore nothing that could reach `ChatRepository`/`BoxChatDatabase`) is present in the
 * tree beneath it -- see the call site in `MainActivity`.
 */
@Composable
fun DatabaseUnlockScreen(activity: FragmentActivity) {
  val context = LocalContext.current
  var statusMessage by remember { mutableStateOf<String?>(null) }
  // Terminal: the Keystore key was invalidated by a biometric enrollment change since encryption
  // was turned on. No amount of retrying will ever succeed -- see the file header. Once true,
  // this never goes back to false; a fresh LaunchedEffect(Unit) on the next process start is the
  // only way this screen resets its own state.
  var keyPermanentlyInvalidated by remember { mutableStateOf(false) }
  var checkedAvailability by remember { mutableStateOf(false) }

  fun attemptUnlock() {
    statusMessage = null
    BiometricEncryptionManager.promptDecrypt(
      activity = activity,
      context = context,
      onSuccess = { cipher ->
        try {
          val passphrase = BiometricEncryptionManager.decryptPassphrase(context, cipher)
          PassphraseHolder.set(passphrase)
          SecurityAuditLog.log(context, "DB_PASSPHRASE_UNLOCKED")
        } catch (e: Exception) {
          // Biometric auth itself succeeded (we have a valid cipher), but unwrapping the stored
          // ciphertext failed regardless -- e.g. corrupted/cleared "enc_passphrase" prefs entry.
          // Not the same as key invalidation: retrying is still meaningful if this was transient.
          Log.e(TAG, "decryptPassphrase failed after successful biometric auth", e)
          SecurityAuditLog.log(context, "DB_PASSPHRASE_UNLOCK_FAILED")
          statusMessage = "Couldn't unlock the database: ${e.message ?: "unknown error"}. Try again."
        }
      },
      onFailure = { _, msg ->
        // A single failed match (e.g. wrong finger). The system prompt stays open and lets the
        // user retry on its own; this just surfaces a message under our button too.
        statusMessage = msg?.toString() ?: "Authentication failed. Try again."
      },
      onError = { code, msg ->
        when (code) {
          BiometricEncryptionManager.ERROR_KEY_PERMANENTLY_INVALIDATED -> {
            keyPermanentlyInvalidated = true
            SecurityAuditLog.log(context, "DB_KEY_PERMANENTLY_INVALIDATED")
          }
          BiometricPrompt.ERROR_USER_CANCELED,
          BiometricPrompt.ERROR_CANCELED,
          BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
            // User backed out of the prompt. Leave this screen showing with its own "Unlock"
            // button so they can retry -- no dead end, and critically no fail-open.
            statusMessage = null
          }
          BiometricPrompt.ERROR_LOCKOUT ->
            statusMessage = "Too many attempts. Wait about 30 seconds, then tap Unlock again."
          BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
            statusMessage =
              "Biometric authentication is locked. Unlock your device screen once with your " +
                "PIN, pattern, or password -- that resets the biometric lockout -- then tap " +
                "Unlock again. (Your device credential cannot decrypt this database directly; " +
                "only the biometric that was enrolled when you turned this on can.)"
          else -> statusMessage = msg.toString()
        }
      },
    )
  }

  // Checked once when the gate first appears (i.e. once per process start). Unlike
  // AppLockScreen, there is no fail-open branch here: if no biometric is currently enrolled,
  // that is reported to the user as a blocking condition to fix (enroll the biometric used when
  // encryption was turned on), not a reason to let them straight through to an unreadable
  // database.
  LaunchedEffect(Unit) {
    if (!checkedAvailability) {
      checkedAvailability = true
      when (BiometricHelper(activity).canAuthenticate()) {
        BiometricHelper.BiometricStatus.AVAILABLE -> attemptUnlock()
        BiometricHelper.BiometricStatus.NOT_ENROLLED ->
          statusMessage =
            "No biometric is enrolled on this device. Enroll the biometric you used when you " +
              "turned on database encryption (Settings > Security), then tap Unlock again."
        BiometricHelper.BiometricStatus.NO_HARDWARE,
        BiometricHelper.BiometricStatus.UNAVAILABLE ->
          statusMessage =
            "Biometric hardware is unavailable right now. This database can only be decrypted " +
              "with the biometric that was enrolled when encryption was turned on."
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
          Icons.Rounded.LockClock,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(bottom = 16.dp),
        )
        if (keyPermanentlyInvalidated) {
          Text("Chat history is unrecoverable", style = MaterialTheme.typography.titleLarge)
          Text(
            "Your biometrics changed since database encryption was turned on. The database key " +
              "was encrypted only under that biometric-bound Keystore key, with no other copy " +
              "kept -- this was called out when you enabled the setting. That key is now " +
              "permanently unusable, so this chat history cannot be decrypted by this app, ever.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
          )
          Text(
            "This app will not wipe or re-key anything on its own. To use Box again you would " +
              "need to clear its app data yourself (Settings > Apps > Box > Storage > Clear " +
              "storage), which also deletes every downloaded model -- there is no smaller " +
              "recovery step.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        } else {
          Text("Database is locked", style = MaterialTheme.typography.titleLarge)
          Text(
            "Authenticate to decrypt your chats",
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
}
