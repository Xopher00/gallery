// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.security

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.ai.edge.gallery.proto.UserData
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

/**
 * Box: Tink-backed replacement for [com.google.ai.edge.gallery.UserDataSerializer], which stored
 * OAuth tokens, secrets, and chat history as plaintext proto. Encrypts with AES256-GCM via an
 * injected [Aead] (Keystore-backed in production, [create]; swappable for JVM unit tests).
 *
 * Migration is implicit: [readFrom] treats a missing [MAGIC] header as a legacy plaintext file
 * and parses it as-is, and the next [writeTo] always encrypts -- no separate migration path.
 */
class EncryptedUserDataSerializer(private val aead: Aead) : Serializer<UserData> {

  companion object {
    // Short magic header identifying a Tink-encrypted payload. Anything else on disk is treated
    // as a pre-existing plaintext proto (the one-time migration case).
    private val MAGIC = "TINKv1:".toByteArray(Charsets.US_ASCII)

    // Associated data binds the ciphertext to this specific store so a ciphertext copied from
    // another DataStore file (e.g. the audit log) would fail to decrypt here.
    private val ASSOCIATED_DATA = "user_data.pb".toByteArray(Charsets.UTF_8)

    private const val KEYSET_NAME = "relay_user_data_keyset"
    private const val PREF_FILE = "relay_user_data_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://relay_user_data"

    /** Production factory: builds the [Aead] from an Android-Keystore-backed Tink keyset. */
    fun create(context: Context): EncryptedUserDataSerializer {
      AeadConfig.register()
      val keysetHandle =
          AndroidKeysetManager.Builder()
              .withSharedPref(context, KEYSET_NAME, PREF_FILE)
              .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
              .withMasterKeyUri(MASTER_KEY_URI)
              .build()
              .keysetHandle
      return EncryptedUserDataSerializer(keysetHandle.getPrimitive(Aead::class.java))
    }
  }

  override val defaultValue: UserData = UserData.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): UserData {
    val bytes = input.readBytes()
    return try {
      if (bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
        val ciphertext = bytes.copyOfRange(MAGIC.size, bytes.size)
        UserData.parseFrom(aead.decrypt(ciphertext, ASSOCIATED_DATA))
      } else {
        // No magic header: a legacy plaintext file (or a brand-new empty one). Parse as-is; the
        // next writeTo() encrypts it.
        UserData.parseFrom(bytes)
      }
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    } catch (exception: GeneralSecurityException) {
      throw CorruptionException("Cannot decrypt user data.", exception)
    }
  }

  override suspend fun writeTo(t: UserData, output: OutputStream) {
    val ciphertext = aead.encrypt(t.toByteArray(), ASSOCIATED_DATA)
    output.write(MAGIC)
    output.write(ciphertext)
  }
}
