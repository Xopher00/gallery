// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.security

import androidx.datastore.core.CorruptionException
import com.google.ai.edge.gallery.proto.AccessTokenData
import com.google.ai.edge.gallery.proto.UserData
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Plain JVM tests for [EncryptedUserDataSerializer]. No Android Keystore and no Robolectric: the
 * serializer takes its [Aead] as a constructor argument, so a software (in-memory) Tink keyset
 * generated via [KeysetHandle.generateNew] stands in for the production
 * Android-Keystore-backed one built by [EncryptedUserDataSerializer.create]. This proves the
 * serializer's own read/write and migration logic; it does NOT exercise
 * [EncryptedUserDataSerializer.create] itself, [AndroidKeysetManager][
 * com.google.crypto.tink.integration.android.AndroidKeysetManager], or the Android Keystore --
 * those require a device/emulator and are out of scope for a JVM unit test.
 */
class EncryptedUserDataSerializerTest {

  private lateinit var aead: Aead
  private lateinit var serializer: EncryptedUserDataSerializer

  @Before
  fun setUp() {
    AeadConfig.register()
    val keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
    aead = keysetHandle.getPrimitive(Aead::class.java)
    serializer = EncryptedUserDataSerializer(aead)
  }

  private fun sampleUserData(): UserData =
      UserData.newBuilder()
          .setAccessTokenData(
              AccessTokenData.newBuilder()
                  .setAccessToken("hf_access_token_123")
                  .setRefreshToken("hf_refresh_token_456")
                  .setExpiresAtMs(1_700_000_000_000L)
                  .build()
          )
          .putSecrets("api_key", "sk-super-secret")
          .build()

  @Test
  fun `round trip encrypts on write and decrypts back to the same UserData`() = runBlocking {
    val original = sampleUserData()

    val output = ByteArrayOutputStream()
    serializer.writeTo(original, output)
    val encodedBytes = output.toByteArray()

    // The bytes on disk must not contain the plaintext secret -- otherwise writeTo did nothing.
    val encodedText = String(encodedBytes, Charsets.ISO_8859_1)
    assert(!encodedText.contains("sk-super-secret")) {
      "encrypted output must not contain the plaintext secret"
    }

    val roundTripped = serializer.readFrom(ByteArrayInputStream(encodedBytes))

    assertEquals(original, roundTripped)
  }

  @Test
  fun `readFrom falls back to plaintext proto when no Tink magic header is present`() =
      runBlocking {
        // Simulates a pre-existing user_data.pb written by the old UserDataSerializer, before
        // this change shipped: a bare serialized UserData proto with no encryption at all. This
        // is the one-time migration path -- there is no separate migration step, readFrom just
        // has to still understand this format.
        val original = sampleUserData()
        val plaintextBytes = original.toByteArray()

        val recovered = serializer.readFrom(ByteArrayInputStream(plaintextBytes))

        assertEquals(original, recovered)
      }

  @Test
  fun `writeTo always re-encrypts, migrating a plaintext file on its first write`() = runBlocking {
    val original = sampleUserData()
    val plaintextBytes = original.toByteArray()

    // Read the legacy plaintext file (fallback path)...
    val recovered = serializer.readFrom(ByteArrayInputStream(plaintextBytes))
    // ...then write it back out. The result must now be the encrypted form, not another copy of
    // the plaintext bytes.
    val output = ByteArrayOutputStream()
    serializer.writeTo(recovered, output)
    val rewritten = output.toByteArray()

    assert(!rewritten.contentEquals(plaintextBytes)) {
      "writeTo must encrypt, not just copy through, a migrated plaintext file"
    }
    assertEquals(original, serializer.readFrom(ByteArrayInputStream(rewritten)))
  }

  @Test
  fun `readFrom throws CorruptionException on garbage bytes with the magic header`() {
    val garbage = "TINKv1:".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 2, 3, 4, 5)

    assertThrows(CorruptionException::class.java) {
      runBlocking { serializer.readFrom(ByteArrayInputStream(garbage)) }
    }
  }
}
