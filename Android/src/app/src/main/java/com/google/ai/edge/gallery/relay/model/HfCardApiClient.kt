// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.util.Log
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val CARD_META_TAG = "AGHfCardMetadata"
private const val HF_MODELS_API_URL = "https://huggingface.co/api/models"

/**
 * Reads a model's card facts through upstream's connection setup. Upstream's parser drops
 * `cardData` and `gated`, and its raw-response method is private, so only the connection is reused.
 */
class HfCardApiClient
@Inject
constructor(@IoDispatcher private val cardIoDispatcher: CoroutineDispatcher) :
  HuggingFaceApiClient(cardIoDispatcher) {

  suspend fun getCardFacts(modelId: String, accessToken: String? = null): HfCardFacts? =
    withContext(cardIoDispatcher) {
      val urlString = "$HF_MODELS_API_URL/$modelId"
      try {
        val connection =
          openHttpConnection(urlString = urlString, method = "GET", accessToken = accessToken)
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          Log.e(CARD_META_TAG, "HF card API returned HTTP ${connection.responseCode} for $urlString")
          return@withContext null
        }

        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
        parseHfCardFacts(JsonParser.parseString(responseText).asJsonObject)
      } catch (e: Exception) {
        Log.e(CARD_META_TAG, "Failed to fetch HF card metadata for $modelId", e)
        null
      }
    }
}
