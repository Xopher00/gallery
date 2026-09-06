package com.google.ai.edge.gallery.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import java.io.File
import java.security.SecureRandom

/**
 * Box: Security utilities for privacy hardening.
 * Ported and extended from OfflineLLM SecurityUtils.
 */
object SecurityUtils {

    private const val TAG = "BoxSecurity"

    /**
     * Sanitize user input before sending to inference engine.
     * Removes null bytes, control characters, and enforces max length.
     */
    fun sanitizePrompt(input: String, maxLength: Int = 4096): String {
        return input
            .take(maxLength)
            .replace("\u0000", "") // Remove null bytes
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "") // Remove control chars
            .trim()
    }

    /**
     * Redact sensitive content for logging.
     * Never log full prompts or responses.
     */
    fun redactForLog(content: String): String {
        if (content.length <= 6) return "[REDACTED]"
        return "${content.take(3)}***${content.takeLast(3)}"
    }

    /**
     * Securely delete a file by overwriting with random bytes before deletion.
     * Three-pass overwrite: random, zeros, random.
     */
    fun secureDelete(file: File): Boolean {
        return try {
            if (!file.exists()) return true

            val random = SecureRandom()
            val buffer = ByteArray(8192)
            val length = file.length()

            // Pass 1: random data
            overwriteFile(file, length) { random.nextBytes(buffer); buffer }
            // Pass 2: zeros
            val zeros = ByteArray(8192)
            overwriteFile(file, length) { zeros }
            // Pass 3: random data again
            overwriteFile(file, length) { random.nextBytes(buffer); buffer }

            // Then delete
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Secure delete failed", e)
            file.delete() // Fallback to regular delete
        }
    }

    private fun overwriteFile(file: File, length: Long, bufferProvider: () -> ByteArray) {
        file.outputStream().use { output ->
            var written = 0L
            while (written < length) {
                val buffer = bufferProvider()
                val toWrite = minOf(buffer.size.toLong(), length - written).toInt()
                output.write(buffer, 0, toWrite)
                written += toWrite
            }
            output.flush()
        }
    }

    /**
     * Validate that a file path is within the allowed sandbox directory.
     * Prevents path traversal attacks.
     */
    fun isPathSandboxed(path: String, sandboxDir: String): Boolean {
        val canonicalPath = File(path).canonicalPath
        val canonicalSandbox = File(sandboxDir).canonicalPath
        return canonicalPath.startsWith(canonicalSandbox)
    }

    /**
     * Copy text to clipboard with the isSensitive flag (API 33+).
     * Marks the clip as sensitive so it won't appear in clipboard preview.
     */
    fun copyToClipboardSensitive(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)
    }

    private const val LEGACY_PREFS_NAME = "box_security"
    private const val LEGACY_KEY = "db_key"
    private const val SECURE_KEY = "db_key"

    // Box: in-memory cache of the *non-biometric* passphrase, keyed off SecurePrefs. Deliberately
    // separate from PassphraseHolder rather than reusing it: PassphraseHolder.isSet is a semantic
    // signal consumed elsewhere (MainActivity gates the biometric unlock screen on it) meaning
    // "the biometric-protected passphrase is currently unlocked in memory". If this cache reused
    // PassphraseHolder, populating it for the non-biometric path would flip isSet=true while
    // biometric encryption is off, which would corrupt that unrelated gate. Keeping a private
    // cache here means the two can never be confused, and getDatabasePassphrase's dispatch
    // (below) is untouched -- it still decides biometric-vs-plain purely from
    // BiometricEncryptionManager.isEnabled() before either cache is ever consulted.
    @Volatile private var cachedPassphrase: ByteArray? = null

    // Guards getOrCreatePassphrase so a background warm-up call (GalleryApplication.onCreate)
    // and a real caller racing on first launch can't both miss the cache and independently hit
    // SecurePrefs/StrongBox, or -- on a genuinely fresh install -- both decide "nothing stored"
    // and each mint and write their own new passphrase.
    private val passphraseLock = Any()

    /**
     * Generate a database encryption passphrase from Android Keystore.
     * If biometric DB encryption is enabled, the passphrase must be in PassphraseHolder
     * (set by MainActivity after biometric auth). Otherwise reads/generates it via
     * [getOrCreatePassphrase] (SecurePrefs, with legacy-plaintext fallback/migration).
     */
    fun getDatabasePassphrase(context: Context): ByteArray {
        if (BiometricEncryptionManager.isEnabled(context)) {
            return PassphraseHolder.get()
                ?: throw IllegalStateException("Database locked: biometric authentication required")
        }
        return getOrCreatePassphrase(context)
    }

    /**
     * Warms [cachedPassphrase] off the main thread so the first database open after process
     * start does not pay for a synchronous StrongBox unwrap on the UI thread. No-op, silently,
     * when biometric DB encryption is on: in that mode the plain passphrase legitimately does
     * not exist in SecurePrefs (it lives only encrypted, unlockable by biometric auth), so there
     * is nothing to warm and attempting it would just throw or -- worse -- read a stale copy.
     * Call from a background dispatcher; safe to call more than once (idempotent once cached).
     */
    fun warmPassphraseCache(context: Context) {
        if (BiometricEncryptionManager.isEnabled(context)) return
        try {
            getOrCreatePassphrase(context)
        } catch (e: Exception) {
            // Degrade to "cache stays cold" -- the real caller on the main thread will read
            // from storage the normal way. Never treat a warm-up failure as license to mint
            // a new passphrase.
            Log.w(TAG, "Passphrase cache warm-up failed; will read from storage on first use", e)
        }
    }

    /**
     * Reads the database passphrase, migrating it out of legacy plaintext SharedPreferences
     * into [SecurePrefs] the first time this runs after upgrade, or generating a fresh one on
     * first install. Read order: SecurePrefs, then legacy plaintext, then generate.
     *
     * SAFETY: a legacy plaintext value is never deleted until the SecurePrefs copy has been
     * written back and read back byte-identical. If SecurePrefs is unavailable, or the
     * read-back does not match, the legacy plaintext copy is left untouched and used as-is —
     * a degraded-but-working state is always preferred over risking the only key that can
     * open the SQLCipher database. A new passphrase is never generated as a response to a
     * storage failure: if neither store yields a value AND SecurePrefs cannot even be probed
     * for availability, this throws rather than risk minting a new key over one that merely
     * became temporarily unreadable (e.g. a Keystore hiccup after migration already deleted
     * the legacy copy).
     */
    fun getOrCreatePassphrase(context: Context): ByteArray = synchronized(passphraseLock) {
        cachedPassphrase?.let { return@synchronized it.copyOf() }

        // 1. SecurePrefs is the current source of truth once migrated.
        val secureExisting = SecurePrefs.getString(context, SECURE_KEY)
        if (secureExisting != null) {
            val decoded = android.util.Base64.decode(secureExisting, android.util.Base64.NO_WRAP)
            cachedPassphrase = decoded.copyOf()
            return@synchronized decoded
        }

        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyExisting = legacyPrefs.getString(LEGACY_KEY, null)
        if (legacyExisting != null) {
            // 2. Legacy plaintext key present: migrate it under the write-verify-delete rule.
            if (writeAndVerifySecure(context, legacyExisting)) {
                legacyPrefs.edit().remove(LEGACY_KEY).apply()
                Log.i(TAG, "Migrated database passphrase from plaintext SharedPreferences to SecurePrefs")
            } else {
                Log.w(
                    TAG,
                    "SecurePrefs migration of database passphrase failed or did not verify; " +
                        "keeping legacy plaintext copy and continuing to use it",
                )
            }
            // Either way, the value we already had is correct — return it, never regenerate.
            val decoded = android.util.Base64.decode(legacyExisting, android.util.Base64.NO_WRAP)
            cachedPassphrase = decoded.copyOf()
            return@synchronized decoded
        }

        // 3. Nothing in either store. Before treating this as "fresh install", confirm
        // SecurePrefs is actually reachable — if it is not, we cannot tell a fresh install
        // apart from an existing key that has become unreadable, so refuse to mint a new one.
        if (!SecurePrefs.isAvailable(context)) {
            throw IllegalStateException(
                "Secure storage is unavailable and no passphrase was found in legacy storage " +
                    "either; refusing to generate a new database passphrase that could silently " +
                    "replace an existing, temporarily-unreadable one.",
            )
        }

        val passphrase = ByteArray(32)
        SecureRandom().nextBytes(passphrase)
        val encoded = android.util.Base64.encodeToString(passphrase, android.util.Base64.NO_WRAP)
        if (!writeAndVerifySecure(context, encoded)) {
            Log.w(TAG, "SecurePrefs write failed for newly generated passphrase; falling back to plaintext storage")
            legacyPrefs.edit().putString(LEGACY_KEY, encoded).apply()
        }
        cachedPassphrase = passphrase.copyOf()
        return@synchronized passphrase
    }

    /**
     * Stores an already-known passphrase (e.g. decrypted out of biometric storage when the
     * user disables biometric database encryption) back into the normal (non-biometric) store.
     * Prefers SecurePrefs, verified by read-back; falls back to legacy plaintext storage on any
     * failure so the app keeps working. Cleans up whichever store it did not end up using.
     */
    fun storePassphrase(context: Context, passphrase: ByteArray) {
        synchronized(passphraseLock) {
            val encoded = android.util.Base64.encodeToString(passphrase, android.util.Base64.NO_WRAP)
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            if (writeAndVerifySecure(context, encoded)) {
                legacyPrefs.edit().remove(LEGACY_KEY).apply()
            } else {
                Log.w(TAG, "SecurePrefs unavailable; storing passphrase in legacy plaintext storage")
                legacyPrefs.edit().putString(LEGACY_KEY, encoded).apply()
            }
            // This is the passphrase the caller (SecuritySettings, disabling biometric
            // encryption) just decrypted and wrote back into non-biometric storage -- it is
            // authoritative now, so the cache must reflect it rather than whatever (or nothing)
            // was cached before. A stale cache here would hand the database a superseded key.
            cachedPassphrase?.fill(0)
            cachedPassphrase = passphrase.copyOf()
        }
    }

    /**
     * Clears the non-biometric passphrase copy from both stores. Used after the passphrase has
     * been moved into biometric-protected storage (PassphraseHolder / BiometricEncryptionManager)
     * and the plain copy is no longer needed.
     */
    fun clearPassphrase(context: Context) {
        synchronized(passphraseLock) {
            SecurePrefs.remove(context, SECURE_KEY)
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(LEGACY_KEY)
                .apply()
            // Called when enabling biometric encryption: the non-biometric copy this cache
            // shadows no longer exists in storage (it has moved into PassphraseHolder /
            // BiometricEncryptionManager). A stale entry here would otherwise let a caller
            // that skips the biometric check keep reading a plaintext key that is supposed to
            // be gone; zero and drop it rather than just dropping the reference.
            cachedPassphrase?.fill(0)
            cachedPassphrase = null
        }
    }

    /**
     * Writes [encoded] to SecurePrefs and reads it back to confirm byte-identical storage
     * before the caller may treat the write as successful. Never deletes anything itself —
     * callers own the safety-rule ordering (write, verify, only then delete the old copy).
     */
    private fun writeAndVerifySecure(context: Context, encoded: String): Boolean {
        if (!SecurePrefs.putString(context, SECURE_KEY, encoded)) return false
        val readBack = SecurePrefs.getString(context, SECURE_KEY)
        return readBack == encoded
    }
}
