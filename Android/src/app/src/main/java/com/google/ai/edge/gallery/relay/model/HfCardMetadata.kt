// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val CARD_META_TAG = "AGHfCardMetadata"
private const val HF_API_BASE_URL = "https://huggingface.co/api/models"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 10_000

/** Cache-backed source of a model's short description. Never throws at its caller. */
class HfCardDescriptions(
  private val store: HfCardDescriptionStore,
  private val accessToken: suspend () -> String?,
) {
  private val perModelLocks = ConcurrentHashMap<String, Mutex>()

  suspend fun ensureDescription(modelId: String): String {
    store.get(modelId)?.let {
      return it.description
    }
    return perModelLocks
      .getOrPut(modelId) { Mutex() }
      .withLock {
        store.get(modelId)
          ?: runCatching {
              val facts = fetchHfCardFacts(modelId, accessToken())
              HfCardEntry(
                description = facts?.toDescription().orEmpty(),
                baseModel = facts?.baseModel,
              )
            }
            .getOrDefault(HfCardEntry(description = ""))
            .also { store.put(modelId, it) }
      }
      .description
  }
}

data class HfCardFacts(
  val baseModel: String?,
  val baseModelRelation: String?,
  val license: String?,
  val pipelineTag: String?,
  val libraryName: String?,
  val gated: Boolean,
)

suspend fun fetchHfCardFacts(modelId: String, accessToken: String? = null): HfCardFacts? =
  withContext(Dispatchers.IO) {
    val urlString = "$HF_API_BASE_URL/$modelId"
    try {
      val connection = URL(urlString).openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      if (!accessToken.isNullOrEmpty()) {
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
      }
      connection.connect()

      if (connection.responseCode != HttpURLConnection.HTTP_OK) {
        Log.e(CARD_META_TAG, "HF card API returned HTTP ${connection.responseCode} for $urlString")
        return@withContext null
      }

      val responseText = connection.inputStream.bufferedReader().use { it.readText() }
      val jsonObj = JsonParser.parseString(responseText).asJsonObject
      parseHfCardFacts(jsonObj)
    } catch (e: Exception) {
      Log.e(CARD_META_TAG, "Failed to fetch HF card metadata for $modelId", e)
      null
    }
  }

private fun parseHfCardFacts(jsonObj: JsonObject): HfCardFacts {
  val cardData = jsonObj.getOrNull("cardData")?.takeIf { it.isJsonObject }?.asJsonObject

  // gated may be `false`, or the string "auto"; anything other than `false` counts as gated.
  val gatedElement = jsonObj.getOrNull("gated")
  val gated = gatedElement != null && !(gatedElement.isJsonPrimitive &&
    gatedElement.asJsonPrimitive.isBoolean && !gatedElement.asBoolean)

  return HfCardFacts(
    baseModel = cardData?.getStringOrFirst("base_model"),
    baseModelRelation = cardData?.getOrNull("base_model_relation")?.asString,
    // `license: other` means the real name is in `license_name`.
    license = cardData?.getOrNull("license_name")?.asString ?: cardData?.getOrNull("license")?.asString,
    pipelineTag = cardData?.getOrNull("pipeline_tag")?.asString,
    libraryName = cardData?.getOrNull("library_name")?.asString,
    gated = gated,
  )
}

// base_model is either a JSON string or an array of strings in the wild; take the first element.
private fun JsonObject.getStringOrFirst(member: String): String? {
  val element = getOrNull(member) ?: return null
  return if (element.isJsonArray) {
    element.asJsonArray.firstOrNull()?.asString
  } else {
    element.asString
  }
}

private fun JsonObject.getOrNull(member: String) =
  if (has(member) && !get(member).isJsonNull) get(member) else null

fun HfCardFacts.toDescription(): String {
  // Group into at most two clauses (lineage+license, capability+library) to stay near
  // a curated one-to-two-sentence blurb rather than one clause per sentence.
  val lineageClauses = mutableListOf<String>()
  baseModel?.let { base ->
    lineageClauses += if (baseModelRelation == "quantized") "Quantized from $base" else "From $base"
  }
  license?.let { lineageClauses += "licensed under $it" }

  val capabilityClauses = mutableListOf<String>()
  pipelineTag?.let { capabilityClauses += "supports ${it.replace('-', ' ')}" }
  libraryName?.let { capabilityClauses += "published with the $it library" }

  val sentences = mutableListOf<String>()
  if (lineageClauses.isNotEmpty()) sentences += lineageClauses.joinToString(", ").capitalizeFirst()
  if (capabilityClauses.isNotEmpty()) sentences += capabilityClauses.joinToString(", ").capitalizeFirst()
  if (gated) sentences += "Access is gated by the repository owner"

  if (sentences.isEmpty()) return ""
  return sentences.joinToString(". ") + "."
}

private fun String.capitalizeFirst(): String =
  if (isEmpty()) this else this[0].uppercaseChar() + substring(1)
