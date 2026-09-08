// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

/*
 * Adapted from jegly/OfflineLLM (https://github.com/jegly/OfflineLLM),
 * commit e81091e86013c0605381d15a1ad7276a4be0b92b.
 * Original file: app/src/main/java/com/jegly/offlineLLM/utils/SignatureVerifier.kt
 * Licensed under the Apache License, Version 2.0; the canonical text at
 * https://www.apache.org/licenses/LICENSE-2.0 (SPDX: Apache-2.0).
 *
 * Changes from the original: repackaged from com.jegly.offlineLLM.utils into
 * com.google.ai.edge.gallery.security; the hard-coded jegly release-certificate
 * digest was replaced with BuildConfig.TRUSTED_SIGNING_CERT_SHA256, a
 * buildConfigField set per build type/signing config rather than a source
 * literal — our release cert digest is populated only in the signed release
 * config, so a debug or CI build (no keystore.properties, falls back to the
 * debug signing config) carries an empty expected value instead of failing
 * every check. Added an explicit "not configured" result for that empty-value
 * case instead of reporting it as a mismatch; result type changed from Boolean
 * to a sealed VerificationResult so "not configured" is distinguishable from
 * "checked and mismatched". Added a checkAndLog(...) helper that records the
 * outcome via SecurityAuditLog, matching this package's existing audit-log
 * convention. This class only reports; it does not gate app startup, refuse
 * to run, or disable any feature on a mismatch.
 */
package com.google.ai.edge.gallery.relay.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import java.security.MessageDigest

/**
 * Checks the running APK's signing certificate against an expected SHA-256
 * digest baked in at build time (BuildConfig.TRUSTED_SIGNING_CERT_SHA256).
 *
 * This is a reporting-only signal for detecting repackaging/re-signing — it
 * does not block startup or disable functionality. Debug and CI builds
 * (signed with the debug key because keystore.properties is absent) carry an
 * empty expected digest, in which case the check reports [NotConfigured]
 * rather than [Mismatched]; that is expected on every fresh clone and CI run
 * and must not be read as a tamper alarm.
 */
class SignatureVerifier(private val context: Context) {

    sealed class VerificationResult {
        /** No expected digest was baked into this build (BuildConfig value empty). */
        object NotConfigured : VerificationResult()

        /** The running APK's signing certificate matches the expected digest. */
        object Trusted : VerificationResult()

        /** The running APK's signing certificate does not match the expected digest. */
        object Mismatched : VerificationResult()

        /** The signature could not be read/hashed (unexpected PackageManager failure). */
        object Error : VerificationResult()
    }

    fun verify(): VerificationResult {
        val expectedDigest = BuildConfig.TRUSTED_SIGNING_CERT_SHA256
        if (expectedDigest.isEmpty()) {
            return VerificationResult.NotConfigured
        }
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                info.signatures ?: emptyArray()
            }
            val md = MessageDigest.getInstance("SHA-256")
            val matches = signatures.any { sig ->
                val digest = md.digest(sig.toByteArray())
                digest.joinToString("") { "%02x".format(it) }.equals(expectedDigest, ignoreCase = true)
            }
            if (matches) VerificationResult.Trusted else VerificationResult.Mismatched
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read signing certificate", e)
            VerificationResult.Error
        }
    }

    /**
     * Runs [verify] and records the outcome to [SecurityAuditLog]. Never throws
     * and never affects app behavior beyond the log entry.
     */
    fun checkAndLog() {
        val result = verify()
        val event = when (result) {
            is VerificationResult.NotConfigured -> "SIGNATURE_CHECK_NOT_CONFIGURED"
            is VerificationResult.Trusted -> "SIGNATURE_CHECK_TRUSTED"
            is VerificationResult.Mismatched -> "SIGNATURE_CHECK_MISMATCHED"
            is VerificationResult.Error -> "SIGNATURE_CHECK_ERROR"
        }
        SecurityAuditLog.log(context, event)
    }

    companion object {
        private const val TAG = "SignatureVerifier"
    }
}
