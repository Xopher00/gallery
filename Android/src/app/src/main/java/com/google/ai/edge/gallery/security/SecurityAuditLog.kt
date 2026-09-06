package com.google.ai.edge.gallery.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Box: Encrypted local audit log for security events.
 * Logs biometric auth attempts, security violations, etc.
 * No network — everything stays on device.
 *
 * EncryptedFile (androidx.security-crypto) has no append mode: openFileOutput()
 * truncates, and it throws if the target file already exists. To keep a
 * single rolling log under that constraint, every write does a full
 * read-decrypt -> append in memory -> delete -> re-encrypt-write cycle. This
 * is O(n) per log call in the size of the existing log, which is acceptable
 * only because MAX_LOG_SIZE bounds that size to 512 KB and this is a
 * low-volume audit log (auth attempts / lock events), not a high-frequency
 * trace. A log call must never crash the app, so every path stays inside
 * try/catch, matching the previous plain-file behavior.
 */
object SecurityAuditLog {

    private const val TAG = "BoxAuditLog"
    private const val LOG_FILE = "box_security_audit.log.enc"
    private const val LEGACY_LOG_FILE = "box_security_audit.log"
    private const val MAX_LOG_SIZE = 512 * 1024 // 512 KB max

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    private fun masterKey(context: Context) =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun encryptedFile(context: Context, file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey(context),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    /**
     * One-time migration: a device may already have the old plaintext log on
     * disk from before this file used EncryptedFile. That log was never
     * really protected (it rationalized "encrypted at rest via filesystem
     * encryption" while writing plain appendText), so rather than trust its
     * contents by copying them into the new encrypted file, it is securely
     * deleted. Best-effort: failures here must not block writing the new
     * entry.
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
     * Read the current decrypted log contents, or "" if there is none yet.
     * Any failure to decrypt (corrupt file, key mismatch) is treated as an
     * empty log rather than propagated, so a write can always proceed.
     */
    private fun readExisting(context: Context, logFile: File): String {
        if (!logFile.exists()) return ""
        return try {
            encryptedFile(context, logFile).openFileInput().use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt existing audit log; discarding it", e)
            ""
        }
    }

    /**
     * Append a security event to the encrypted audit log.
     */
    fun log(context: Context, event: String) {
        try {
            migrateLegacyLogIfPresent(context)

            val logFile = File(context.filesDir, LOG_FILE)
            val entry = "${dateFormat.format(Date())} | $event\n"

            var existing = readExisting(context, logFile)

            // Rotate if too large: drop the old contents rather than let the
            // decrypt/append/rewrite cycle grow unbounded.
            if (existing.toByteArray(Charsets.UTF_8).size > MAX_LOG_SIZE) {
                existing = ""
            }

            // EncryptedFile has no append mode and refuses to write over an
            // existing file, so the old encrypted file must be removed
            // before the combined contents can be written back.
            if (logFile.exists()) {
                SecurityUtils.secureDelete(logFile)
            }

            encryptedFile(context, logFile).openFileOutput().use { output ->
                output.write((existing + entry).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write audit log", e)
        }
    }

    /**
     * Read the audit log contents.
     */
    fun readLog(context: Context): String {
        return try {
            val logFile = File(context.filesDir, LOG_FILE)
            if (!logFile.exists()) return "(empty)"
            val contents = readExisting(context, logFile)
            if (contents.isEmpty()) "(empty)" else contents
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
