// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * Adapted from jegly/OfflineLLM (https://github.com/jegly/OfflineLLM),
 * commit e81091e86013c0605381d15a1ad7276a4be0b92b.
 * Original file: app/src/main/java/com/jegly/offlineLLM/utils/BiometricHelper.kt
 * Licensed under the Apache License, Version 2.0; the canonical text at
 * https://www.apache.org/licenses/LICENSE-2.0 (SPDX: Apache-2.0).
 *
 * Changes from the original: repackaged from com.jegly.offlineLLM.utils into
 * com.google.ai.edge.gallery.security; prompt title changed from "offlineLLM" to
 * "Box"; added SecurityAuditLog.log(...) calls on auth success/failure/error,
 * matching this package's existing audit-log convention.
 */
package com.google.ai.edge.gallery.relay.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Simple biometric authentication gate (e.g. for an app-resume lock screen).
 */
class BiometricHelper(private val activity: FragmentActivity) {

    fun canAuthenticate(): BiometricStatus {
        val biometricManager = BiometricManager.from(activity)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            else -> BiometricStatus.UNAVAILABLE
        }
    }

    fun authenticate(
        onSuccess: () -> Unit,
        onFailure: (Int, CharSequence?) -> Unit,
        onError: (Int, CharSequence) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                SecurityAuditLog.log(activity, "BIOMETRIC_AUTH_SUCCESS")
                onSuccess()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                SecurityAuditLog.log(activity, "BIOMETRIC_AUTH_FAILED")
                onFailure(-1, "Authentication failed")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                SecurityAuditLog.log(activity, "BIOMETRIC_AUTH_ERROR: code=$errorCode")
                onError(errorCode, errString)
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Box")
            .setSubtitle("Authenticate to access your chats")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }

    enum class BiometricStatus {
        AVAILABLE,
        NO_HARDWARE,
        UNAVAILABLE,
        NOT_ENROLLED
    }

    // --- App-lock (resume gate) additions below. ---
    //
    // canAuthenticate()/authenticate() above check BIOMETRIC_STRONG only, which is what a
    // CryptoObject-bound key would require (only a class-3 biometric can release one).
    // The app-resume lock has no CryptoObject to bind to -- it is a plain gate --
    // so per this card's owner decision it accepts BIOMETRIC_STRONG or DEVICE_CREDENTIAL, making
    // the device PIN/pattern/password a built-in fallback. minSdk is 31 here (see
    // Android/src/gradle/libs.versions.toml's androidx-biometric = 1.2.0-alpha05), above the
    // level where combining DEVICE_CREDENTIAL with a biometric authenticator was restricted, and
    // per the androidx.biometric API contract, setNegativeButtonText() must NOT be called when
    // DEVICE_CREDENTIAL is one of the allowed authenticators -- BiometricPrompt throws
    // IllegalArgumentException if both are set. This is why the prompt below has no negative
    // button: the device back button is the way to dismiss it, surfaced as
    // ERROR_USER_CANCELED/ERROR_CANCELED in onError.

    private val appUnlockAuthenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticateForAppUnlock(): BiometricStatus {
        val biometricManager = BiometricManager.from(activity)
        return when (biometricManager.canAuthenticate(appUnlockAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            else -> BiometricStatus.UNAVAILABLE
        }
    }

    fun authenticateForAppUnlock(
        onSuccess: () -> Unit,
        onFailure: (Int, CharSequence?) -> Unit,
        onError: (Int, CharSequence) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                SecurityAuditLog.log(activity, "APP_UNLOCK_SUCCESS")
                onSuccess()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                SecurityAuditLog.log(activity, "APP_UNLOCK_FAILED")
                onFailure(-1, "Authentication failed")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                SecurityAuditLog.log(activity, "APP_UNLOCK_ERROR: code=$errorCode")
                onError(errorCode, errString)
            }
        }

        // No setNegativeButtonText(): forbidden by the API when DEVICE_CREDENTIAL is included
        // in setAllowedAuthenticators() -- see the note above this section.
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Box")
            .setSubtitle("Authenticate to continue")
            .setAllowedAuthenticators(appUnlockAuthenticators)
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }
}
