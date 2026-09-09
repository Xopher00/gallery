// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Box: Encrypted local audit log for security events.
 * Logs biometric auth attempts, security violations, etc.
 * No network — everything stays on device.
 *
 * Tink [Aead] (unlike the deprecated encrypted-file API this replaces, which had no append mode)
 * lets each log entry be encrypted independently, so the log is genuinely append-only: every entry is
 * `aead.encrypt(line)` -> Base64 -> one line appended to a plain `File`. There is no
 * read-decrypt-append-rewrite cycle and therefore no O(n)-per-write cost or rotation-on-write
 * requirement. A log call must never crash the app, so every path stays inside try/catch, matching
 * the previous behavior.
 */
object SecurityAuditLog {

    private const val TAG = "BoxAuditLog"
    private const val LOG_FILE = "box_security_audit.log.enc"
    private const val LEGACY_LOG_FILE = "box_security_audit.log"
    private const val MAX_LOG_SIZE = 512 * 1024 // 512 KB max

    private const val KEYSET_NAME = "relay_audit_log_keyset"
    private const val PREF_FILE = "relay_audit_log_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://relay_audit_log"
    private val ASSOCIATED_DATA = "box_security_audit".toByteArray(Charsets.UTF_8)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    private fun aead(context: Context): Aead {
        AeadConfig.register()
        val keysetHandle =
            AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
        // getPrimitive(Class) is deprecated in Tink 1.23; RegistryConfiguration.get() reads the
        // same global registry AeadConfig.register() just populated, so behavior is unchanged.
        return keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /**
     * One-time migration: a device may already have the old plaintext log on
     * disk from before this file was encrypted at all. That log was never
     * really protected (it rationalized "encrypted at rest via filesystem
     * encryption" while writing plain appendText), so rather than trust its
     * contents, it is securely deleted. Best-effort: failures here must not
     * block writing the new entry.
     */
    private fun migrateLegacyLogIfPresent(context: Context) {
        try {
            val legacyFile = File(context.filesDir, LEGACY_LOG_FILE)
            if (legacyFile.exists()) {
                SecurityUtils.secureDelete(legacyFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove legacy plaintext audit log", e)
        }
    }

    /**
     * Append a security event to the encrypted audit log. Genuinely append-only: the existing
     * file is never read, decrypted, or rewritten -- only one new encrypted line is added.
     */
    fun log(context: Context, event: String) {
        try {
            migrateLegacyLogIfPresent(context)

            val logFile = File(context.filesDir, LOG_FILE)

            // Rotate if too large: drop the old log rather than grow it unbounded. There is no
            // decrypt/append/rewrite cycle to bound the cost of, but the log is still meant to be
            // a small rolling window, not an unbounded trace.
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                SecurityUtils.secureDelete(logFile)
            }

            val entry = "${dateFormat.format(Date())} | $event"
            val ciphertext = aead(context).encrypt(entry.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
            val line = Base64.encodeToString(ciphertext, Base64.NO_WRAP) + "\n"
            logFile.appendText(line, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write audit log", e)
        }
    }

    /**
     * Read the audit log contents: each line is Base64-decoded and decrypted independently. A
     * line that fails to decrypt (corrupt entry, key mismatch) is skipped rather than failing the
     * whole read.
     */
    fun readLog(context: Context): String {
        return try {
            val logFile = File(context.filesDir, LOG_FILE)
            if (!logFile.exists()) return "(empty)"

            val cipher = aead(context)
            val lines =
                logFile
                    .readLines(Charsets.UTF_8)
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            val ciphertext = Base64.decode(line, Base64.NO_WRAP)
                            String(cipher.decrypt(ciphertext, ASSOCIATED_DATA), Charsets.UTF_8)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to decrypt audit log line; skipping", e)
                            null
                        }
                    }
            if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read audit log", e)
            "(error reading log)"
        }
    }

    /**
     * Securely wipe the audit log.
     */
    fun clearLog(context: Context) {
        try {
            val logFile = File(context.filesDir, LOG_FILE)
            if (logFile.exists()) {
                SecurityUtils.secureDelete(logFile)
            }
            migrateLegacyLogIfPresent(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear audit log", e)
        }
    }
}
