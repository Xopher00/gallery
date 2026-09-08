// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server

import android.content.Context
import android.util.Base64
import com.google.ai.edge.gallery.data.DataStoreRepository
import java.security.MessageDigest
import java.security.SecureRandom

object ApiKey {
    private const val PREFS_NAME = "openai_server_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val SECRET_KEY_API_KEY = "openai_server_api_key"

    fun generateKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** First 6 hex chars of SHA-256(key) -- a short, non-secret way to tell keys apart in the UI. */
    fun fingerprint(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(6)
    }

    /** Generates on first access; migrates a legacy plaintext key from prefs if present. */
    fun apiKey(context: Context, repo: DataStoreRepository): String {
        val existing = repo.readSecret(SECRET_KEY_API_KEY)
        if (existing != null && existing.isNotBlank()) return existing

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = prefs.getString(KEY_API_KEY, null)
        if (legacy != null && legacy.isNotBlank()) {
            repo.saveSecret(SECRET_KEY_API_KEY, legacy)
            prefs.edit().remove(KEY_API_KEY).apply()
            return legacy
        }

        val key = generateKey()
        repo.saveSecret(SECRET_KEY_API_KEY, key)
        return key
    }

    fun regenerateApiKey(context: Context, repo: DataStoreRepository): String {
        val key = generateKey()
        repo.saveSecret(SECRET_KEY_API_KEY, key)
        // Defensive: an unmigrated plaintext copy would otherwise hold a now-stale key.
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_API_KEY).apply()

        // A running server captured the old key at start() and won't see this write.
        if (ServerRuntime.isRunning.value) {
            OpenAiServerService.startService(context.applicationContext)
        }
        return key
    }
}
