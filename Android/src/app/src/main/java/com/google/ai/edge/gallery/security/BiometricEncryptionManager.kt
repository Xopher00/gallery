package com.google.ai.edge.gallery.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

object BiometricEncryptionManager {

    private const val KEY_ALIAS = "box_db_enc_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val PREFS_NAME = "box_db_enc"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ENC_PASSPHRASE = "enc_passphrase"
    private const val KEY_IV = "iv"

    private val _isEnabledFlow = MutableStateFlow(false)
    val isEnabledFlow: StateFlow<Boolean> = _isEnabledFlow.asStateFlow()

    fun init(context: Context) {
        _isEnabledFlow.value = isEnabled(context)
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun getEncryptCipher(): Cipher {
        val key = getOrCreateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    fun getDecryptCipher(context: Context): Cipher {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ivB64 = prefs.getString(KEY_IV, null) ?: error("No IV stored")
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    fun storeEncryptedPassphrase(context: Context, cipher: Cipher, plainPassphrase: ByteArray) {
        val encrypted = cipher.doFinal(plainPassphrase)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENC_PASSPHRASE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putBoolean(KEY_ENABLED, true)
            .apply()
        _isEnabledFlow.value = true
    }

    fun decryptPassphrase(context: Context, cipher: Cipher): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encB64 = prefs.getString(KEY_ENC_PASSPHRASE, null) ?: error("No encrypted passphrase stored")
        return cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP))
    }

    fun disable(context: Context) {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {}
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        _isEnabledFlow.value = false
    }

    fun getHardwareLevel(): String {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return "None"
            val factory = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE_PROVIDER)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            when (keyInfo.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                else -> "Software"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    fun promptEncrypt(
        activity: FragmentActivity,
        onSuccess: (Cipher) -> Unit,
        onFailure: (Int, CharSequence?) -> Unit,
        onError: (Int, CharSequence) -> Unit,
    ) {
        val cipher = try {
            getEncryptCipher()
        } catch (e: Exception) {
            onError(-1, "Failed to prepare encryption: ${e.message}")
            return
        }
        showPrompt(
            activity = activity,
            subtitle = "Authenticate to enable database encryption",
            cryptoObject = BiometricPrompt.CryptoObject(cipher),
            onSuccess = { result -> result.cryptoObject?.cipher?.let(onSuccess) },
            onFailure = onFailure,
            onError = onError,
        )
    }

    fun promptDecrypt(
        activity: FragmentActivity,
        context: Context,
        onSuccess: (Cipher) -> Unit,
        onFailure: (Int, CharSequence?) -> Unit,
        onError: (Int, CharSequence) -> Unit,
    ) {
        val cipher = try {
            getDecryptCipher(context)
        } catch (e: Exception) {
            onError(-1, "Failed to prepare decryption: ${e.message}")
            return
        }
        showPrompt(
            activity = activity,
            subtitle = "Authenticate to decrypt your chats",
            cryptoObject = BiometricPrompt.CryptoObject(cipher),
            onSuccess = { result -> result.cryptoObject?.cipher?.let(onSuccess) },
            onFailure = onFailure,
            onError = onError,
        )
    }

    private fun showPrompt(
        activity: FragmentActivity,
        subtitle: String,
        cryptoObject: BiometricPrompt.CryptoObject,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onFailure: (Int, CharSequence?) -> Unit,
        onError: (Int, CharSequence) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess(result)
            }
            override fun onAuthenticationFailed() {
                onFailure(-1, "Authentication failed")
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errorCode, errString)
            }
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Box")
            .setSubtitle(subtitle)
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        BiometricPrompt(activity, executor, callback).authenticate(promptInfo, cryptoObject)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        return try {
            createKey(strongBox = true)
        } catch (_: Exception) {
            createKey(strongBox = false)
        }
    }

    private fun createKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        kg.init(spec)
        return kg.generateKey()
    }
}
