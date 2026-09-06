package com.jegly.offlineLLM.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

class SignatureVerifier(private val context: Context) {

    fun isSignedByTrustedCert(): Boolean = try {
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
        signatures.any { sig ->
            val digest = md.digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) } == TRUSTED_DIGEST
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        // JEGLY keystore SHA-256 — offlineLLM release APK
        private const val TRUSTED_DIGEST = "98d324d4106a368c62729a0a24d9ac9a6b47f8ac4c6585348531f0ee4eb6a04c"
    }
}
