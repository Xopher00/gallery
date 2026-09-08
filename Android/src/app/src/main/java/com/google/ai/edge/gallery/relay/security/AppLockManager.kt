// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.security

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Box: App lock manager that observes app lifecycle.
 * Enforces biometric authentication when app comes to foreground.
 */
object AppLockManager {

    private const val PREFS_NAME = "box_settings"
    private const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
    private const val KEY_SCREENSHOTS_ENABLED = "screenshots_enabled"

    private val _isUnlocked = MutableStateFlow(true)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _screenshotsEnabled = MutableStateFlow(true)
    val screenshotsEnabled: StateFlow<Boolean> = _screenshotsEnabled.asStateFlow()

    private var isBiometricLockEnabled = false

    fun init(context: Context) {
        val prefs = getPrefs(context)
        isBiometricLockEnabled = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        _screenshotsEnabled.value = prefs.getBoolean(KEY_SCREENSHOTS_ENABLED, true)
        
        // Observe app lifecycle
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (isBiometricLockEnabled) {
                    _isUnlocked.value = false
                    SecurityAuditLog.log(context, "APP_LOCKED")
                }
            }
        })
    }

    fun setBiometricLockEnabled(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, enabled).apply()
        isBiometricLockEnabled = enabled
        SecurityAuditLog.log(context, "BIOMETRIC_LOCK_${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun unlock() {
        _isUnlocked.value = true
    }

    fun isBiometricLockEnabled(): Boolean = isBiometricLockEnabled

    fun setScreenshotsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCREENSHOTS_ENABLED, enabled).apply()
        _screenshotsEnabled.value = enabled
    }

    fun isScreenshotsEnabled(): Boolean = _screenshotsEnabled.value

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
