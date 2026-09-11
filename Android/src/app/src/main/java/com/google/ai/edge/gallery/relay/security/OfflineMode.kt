// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.security

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Box: Offline-only mode manager.
 * When enabled, blocks all network requests. Model downloads throw an exception.
 */
object OfflineMode {

    private const val PREFS_NAME = "box_settings"
    private const val KEY_OFFLINE_MODE = "offline_mode_enabled"

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "localhost")

    fun isLoopbackHost(host: String?): Boolean = host != null && host in LOOPBACK_HOSTS

    // A null URL is not network traffic, so it is allowed.
    fun allowsUrl(url: Uri?): Boolean {
        if (url == null) return true
        if (!_isEnabled.value) return true
        if (url.scheme != "http" && url.scheme != "https") return true
        return isLoopbackHost(url.host)
    }

    fun init(context: Context) {
        val prefs = getPrefs(context)
        _isEnabled.value = prefs.getBoolean(KEY_OFFLINE_MODE, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, enabled).apply()
        _isEnabled.value = enabled
        SecurityAuditLog.log(context, "OFFLINE_MODE_${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun toggle(context: Context) {
        setEnabled(context, !_isEnabled.value)
    }

    /**
     * Call this before any network operation. Throws if offline mode is enabled.
     */
    fun assertOnlineOrThrow() {
        if (_isEnabled.value) {
            throw OfflineModeException("Network request blocked: Box is in offline-only mode")
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    class OfflineModeException(message: String) : RuntimeException(message)
}
