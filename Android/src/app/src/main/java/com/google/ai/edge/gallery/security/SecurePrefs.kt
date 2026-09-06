/*
 * Adapted from: jegly/OfflineLLM
 * Original file: Android/src/third_party/offlinellm/SettingsRepository.kt
 * Upstream commit: e81091e86013c0605381d15a1ad7276a4be0b92b
 * Licence: Apache License, Version 2.0 — see Android/src/third_party/offlinellm/LICENSE
 *
 * Changes from upstream:
 *  - Extracted only the encrypted-storage mechanism (master-key creation with
 *    StrongBox/TEE fallback, EncryptedSharedPreferences setup) from the original
 *    262-line SettingsRepository.kt; none of its temperature/max-tokens/theme
 *    settings accessors were carried over.
 *  - Renamed the class from SettingsRepository to SecurePrefs and moved it out
 *    of the com.jegly.offlineLLM package into this project's own
 *    com.google.ai.edge.gallery.security package.
 *  - Uses a distinct preferences file name ("box_secure_prefs") and master-key
 *    alias ("box_secure_prefs_master_key") so this does not collide with
 *    upstream's "offlinellm_secure_prefs" / "offlinellm_secure_prefs_master_key",
 *    nor with this project's existing plain-text "box_security" / "box_settings"
 *    SharedPreferences files.
 *  - Converted from a Hilt @Singleton class to a plain `object` taking a
 *    Context parameter per call, matching the convention already used by the
 *    other files in this package (OfflineMode.kt, SecurityUtils.kt), rather
 *    than introducing Hilt DI into a package that does not otherwise use it.
 *  - Reduced the API surface to a minimal String get/put plus the
 *    secureStorageBackend accessor. No callers are wired up in this change —
 *    this class is not yet used to store the database passphrase or anything
 *    else; that is a separate, gated follow-up decision.
 *  - Wrapped every public entry point in try/catch so a Keystore/StrongBox
 *    failure cannot crash the app, in addition to the three-layer defensive
 *    fallback already present in createMasterKeyAndBackend().
 */
package com.google.ai.edge.gallery.security

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Box: minimal encrypted key-value storage, adapted from OfflineLLM's
 * SettingsRepository (see file header for provenance and licence).
 *
 * NOTE: androidx.security-crypto is deprecated upstream by Google in favour of
 * using the Android Keystore directly. It is adopted here anyway because the
 * legacy alternative (see SecurityUtils.getOrCreatePassphrase's plaintext fallback
 * path) stores the database passphrase Base64-encoded in a plain SharedPreferences
 * file. This class is itself a future migration candidate, not a long-term
 * recommendation — a later pass may replace it with direct Keystore-backed AES-GCM.
 *
 * SecurityUtils.getOrCreatePassphrase/storePassphrase/clearPassphrase now use this
 * as the primary store for the database passphrase, migrating any legacy plaintext
 * value the first time they run and falling back to plaintext storage if this class
 * is ever unavailable.
 */
object SecurePrefs {

    private const val TAG = "BoxSecurePrefs"
    private const val PREFS_FILE_NAME = "box_secure_prefs"
    private const val MASTER_KEY_ALIAS = "box_secure_prefs_master_key"

    @Volatile
    private var cachedBackend: String? = null

    /**
     * Which keystore backend secured the master key: "StrongBox" or "TEE".
     * Returns "unknown" if it could not be determined (e.g. Keystore failure).
     */
    fun secureStorageBackend(context: Context): String {
        cachedBackend?.let { return it }
        return try {
            val (_, backend) = createMasterKeyAndBackend(context)
            cachedBackend = backend
            backend
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine secure storage backend", e)
            "unknown"
        }
    }

    /**
     * Reads a String value from encrypted storage, or [default] if absent or
     * if encrypted storage could not be opened.
     */
    fun getString(context: Context, key: String, default: String? = null): String? {
        return try {
            getPrefs(context)?.getString(key, default) ?: default
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read secure pref for key=$key", e)
            default
        }
    }

    /**
     * Writes a String value to encrypted storage. Returns true on success.
     */
    fun putString(context: Context, key: String, value: String): Boolean {
        return try {
            val prefs = getPrefs(context) ?: return false
            prefs.edit().putString(key, value).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write secure pref for key=$key", e)
            false
        }
    }

    /**
     * Whether encrypted storage can currently be opened at all (master key + backing
     * EncryptedSharedPreferences file). Used to tell a genuinely-absent key apart from a
     * key that merely can't be read right now due to a Keystore/backend failure — [getString]
     * returns null in both cases, which is not enough on its own for a caller that must never
     * mistake "storage is broken" for "there is nothing stored here yet".
     */
    fun isAvailable(context: Context): Boolean {
        return try {
            getPrefs(context) != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to probe secure storage availability", e)
            false
        }
    }

    /**
     * Removes a value from encrypted storage. Returns true on success (including
     * when the key was already absent).
     */
    fun remove(context: Context, key: String): Boolean {
        return try {
            val prefs = getPrefs(context) ?: return false
            prefs.edit().remove(key).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove secure pref for key=$key", e)
            false
        }
    }

    private fun getPrefs(context: Context): SharedPreferences? {
        return try {
            val (masterKey, backend) = createMasterKeyAndBackend(context)
            cachedBackend = backend
            EncryptedSharedPreferences.create(
                PREFS_FILE_NAME,
                MASTER_KEY_ALIAS,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open encrypted preferences", e)
            null
        }
    }

    // Adapted from OfflineLLM's SettingsRepository.createMasterKeyAndBackend():
    // prefer a StrongBox-backed master key, but degrade to a plain TEE-backed
    // key on any failure rather than throw. StrongBox availability varies by
    // device/OS version and is probed defensively in three independent,
    // separately-caught ways below.
    private fun createMasterKeyAndBackend(context: Context): Pair<MasterKey, String> {
        val strongBoxAvailable = try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } catch (_: Exception) {
            false
        }

        return try {
            val requestedKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()

            // Jetpack Security doesn't consistently expose StrongBox state across
            // versions. Use best-effort reflection; fall back to feature detection.
            val reflectStrongBox = runCatching {
                val m = requestedKey.javaClass.getMethod("isStrongBoxBacked")
                (m.invoke(requestedKey) as? Boolean) ?: false
            }.getOrDefault(false)

            val backend = when {
                strongBoxAvailable -> "StrongBox" // Prioritize system feature detection
                reflectStrongBox -> "StrongBox"   // Fallback to reflection
                else -> "TEE"
            }
            requestedKey to backend
        } catch (_: Exception) {
            val fallbackKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            fallbackKey to "TEE"
        }
    }
}
