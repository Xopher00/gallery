/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.huggingface

import android.util.Log
import androidx.core.net.toUri
import com.google.ai.edge.gallery.data.ModelAccessibility
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.proto.HfSiblingProto
import com.google.ai.edge.gallery.proto.HfSortOptionProto
import com.google.ai.edge.gallery.proto.hfModelItemProto
import com.google.ai.edge.gallery.proto.hfSiblingProto
import com.google.ai.edge.gallery.huggingface.heuristics.hasGgufFiles
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

private const val TAG = "AGHfApiClient"
private const val HF_API_BASE_URL = "https://huggingface.co/api"
private const val USER_AGENT = "AIEdgeGallery/1.0 (Android)"
// HF's `filter` query param is AND-combined server-side, so litert-lm and gguf repos
// require two separate requests, merged client-side (see fetchModels).
private const val FILTER_LITERT_LM = "litert-lm"
private const val FILTER_GGUF = "gguf"

open class HuggingFaceApiClient
@Inject
constructor(@IoDispatcher private val ioDispatcher: CoroutineDispatcher) {
  /**
   * Fetches models from the Hugging Face API and applies official community promotion sorting.
   * Search fetching and model sorting are decoupled via [fetchModels] and [sortModels].
   */
  suspend fun searchModels(
    query: String = "",
    sort: HfSortOptionProto = HfSortOptionProto.HF_SORT_OPTION_DOWNLOADS,
    limit: Int = 50,
    accessToken: String? = null,
  ): List<HfModelItemProto> {
    val rawModels = fetchModels(query = query, limit = limit, accessToken = accessToken)
    return sortModels(rawModels, sort)
  }

  /** Fetches matching catalog items from Hugging Face API without imposing a display sort order. */
  suspend fun fetchModels(
    query: String = "",
    limit: Int = 50,
    accessToken: String? = null,
  ): List<HfModelItemProto> =
    withContext(ioDispatcher) {
      // Two independent HF requests (AND-combined filter can't do both in one): run concurrently.
      val litertModels = async {
        fetchModelsForFilter(
          filterTag = FILTER_LITERT_LM,
          query = query,
          limit = limit,
          accessToken = accessToken,
        )
      }
      val ggufModels = async {
        fetchModelsForFilter(
          filterTag = FILTER_GGUF,
          query = query,
          limit = limit,
          accessToken = accessToken,
        )
      }
      return@withContext (litertModels.await() + ggufModels.await()).distinctBy { it.id }
    }

  private fun fetchModelsForFilter(
    filterTag: String,
    query: String,
    limit: Int,
    accessToken: String?,
  ): List<HfModelItemProto> {
    val urlString = buildApiUrl(query = query, limit = limit, filterTag = filterTag)
    val responseText = executeGetRequest(urlString = urlString, accessToken = accessToken)
    if (responseText.isNullOrEmpty()) {
      return emptyList()
    }
    return parseModelListJson(responseText)
  }

  private fun parseModelListJson(responseText: String): List<HfModelItemProto> {
    val jsonElement =
      try {
        JsonParser.parseString(responseText)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse model list JSON", e)
        return emptyList()
      }
    if (!jsonElement.isJsonArray) {
      return emptyList()
    }
    val jsonArray = jsonElement.asJsonArray
    val models = jsonArray.mapNotNull { element ->
      if (element.isJsonObject) parseHfModelItemProto(element.asJsonObject) else null
    }
    return models.filter { it.hasCompatibleModelFiles() || it.hasGgufFiles() }
  }

  private fun buildApiUrl(query: String, limit: Int, filterTag: String): String {
    val queryParams =
      mutableListOf(
        "filter" to filterTag,
        "limit" to limit.toString(),
        "expand" to "siblings",
        "expand" to "tags",
        "expand" to "likes",
        "expand" to "downloads",
        "expand" to "lastModified",
      )
    if (query.isNotBlank()) {
      queryParams.add("search" to URLEncoder.encode(query.trim(), "UTF-8"))
    }
    val queryString = queryParams.joinToString("&") { (key, value) -> "$key=$value" }
    return "$HF_API_BASE_URL/models?$queryString"
  }

  open suspend fun getModelDetails(
    modelId: String,
    accessToken: String? = null,
  ): HfModelItemProto? =
    withContext(ioDispatcher) {
      val urlString = "$HF_API_BASE_URL/models/$modelId?blobs=true"
      val responseText = executeGetRequest(urlString = urlString, accessToken = accessToken)

      if (responseText.isNullOrEmpty()) {
        return@withContext null
      }
      val jsonElement = JsonParser.parseString(responseText)
      if (jsonElement.isJsonObject) {
        return@withContext parseHfModelItemProto(jsonElement.asJsonObject)
      }
      return@withContext null
    }

  /**
   * Fetches the file size in bytes for a specific model file within a Hugging Face repository.
   *
   * @param modelId The Hugging Face model repository ID (e.g. "google/gemma-3-1b-it-litertlm").
   * @param fileName The target model file name or relative path (e.g.
   *   "gemma-3-1b-it-gpu-int4.litertlm").
   * @param accessToken Optional Bearer access token for authenticated API quota.
   * @return The file size in bytes if found and positive, or `null` otherwise.
   */
  open suspend fun getModelFileSize(
    modelId: String,
    fileName: String,
    accessToken: String? = null,
  ): Long? =
    withContext(ioDispatcher) {
      val details = getModelDetails(modelId, accessToken = accessToken) ?: return@withContext null
      val matchingSibling =
        details.siblingsList.firstOrNull {
          it.rfilename == fileName || it.rfilename.endsWith("/$fileName")
        }
      if (matchingSibling != null && matchingSibling.size > 0L) {
        return@withContext matchingSibling.size
      }
      return@withContext null
    }

  /**
   * Probes the accessibility of a Hugging Face model URL using an optional Bearer access token.
   *
   * @param modelUrl The download URL of the model.
   * @param accessToken Optional Bearer access token for authorization.
   * @return [ModelAccessibility] indicating whether the model is accessible, gated, or needs token
   *   exchange.
   */
  open suspend fun checkModelAccessibility(
    modelUrl: String,
    accessToken: String? = null,
  ): ModelAccessibility =
    withContext(ioDispatcher) {
      if (modelUrl.isEmpty()) {
        return@withContext ModelAccessibility.ACCESSIBLE
      }
      val responseCode =
        try {
          val connection =
            openHttpConnection(urlString = modelUrl, method = "HEAD", accessToken = accessToken)
          connection.connect()
          connection.responseCode
        } catch (e: Exception) {
          Log.e(TAG, "Failed to probe model accessibility for URL: $modelUrl", e)
          return@withContext ModelAccessibility.ERROR
        }

      when (responseCode) {
        in 200..299 -> ModelAccessibility.ACCESSIBLE
        HttpURLConnection.HTTP_UNAUTHORIZED -> ModelAccessibility.NEEDS_TOKEN_EXCHANGE
        HttpURLConnection.HTTP_FORBIDDEN -> {
          if (accessToken.isNullOrEmpty()) {
            ModelAccessibility.NEEDS_TOKEN_EXCHANGE
          } else {
            ModelAccessibility.GATED
          }
        }
        else -> {
          Log.w(TAG, "Unexpected response code checking HF model accessibility: $responseCode")
          ModelAccessibility.ERROR
        }
      }
    }

  /**
   * Fetches a repo's model card (README.md) with YAML frontmatter stripped. Gated repos return
   * HTTP 401 without [accessToken].
   */
  open suspend fun fetchModelCard(modelId: String, accessToken: String? = null): String? =
    withContext(ioDispatcher) {
      val urlString = "https://huggingface.co/$modelId/raw/main/README.md"
      val responseText =
        executeGetRequest(urlString = urlString, accessToken = accessToken)
          ?: return@withContext null
      stripFrontmatter(responseText)
    }

  private fun stripFrontmatter(markdown: String): String {
    if (!markdown.startsWith("---")) return markdown
    val end = markdown.indexOf("\n---", startIndex = 3)
    if (end == -1) return markdown
    val afterDelimiter = markdown.indexOf('\n', startIndex = end + 1)
    return if (afterDelimiter == -1) "" else markdown.substring(afterDelimiter + 1).trimStart('\n')
  }

  /**
   * Opens and configures an [HttpURLConnection] with default headers and optional authorization.
   */
  protected open fun openHttpConnection(
    urlString: String,
    method: String = "GET",
    accessToken: String? = null,
  ): HttpURLConnection {
    val url = URL(urlString)
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.setRequestProperty("User-Agent", USER_AGENT)
    if (!accessToken.isNullOrEmpty()) {
      connection.setRequestProperty("Authorization", "Bearer $accessToken")
    }
    return connection
  }

  private fun executeGetRequest(urlString: String, accessToken: String? = null): String? {
    Log.d(TAG, "Executing HF HTTP GET request: $urlString")
    return try {
      val connection =
        openHttpConnection(urlString = urlString, method = "GET", accessToken = accessToken)
      connection.connect()

      val responseCode = connection.responseCode
      if (responseCode == HttpURLConnection.HTTP_OK) {
        connection.inputStream.bufferedReader().use { it.readText() }
      } else {
        Log.e(TAG, "HF API returned HTTP $responseCode for URL: $urlString")
        null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed HTTP GET request to HF API for URL: $urlString", e)
      null
    }
  }

  private fun parseHfModelItemProto(jsonObj: JsonObject): HfModelItemProto = hfModelItemProto {
    jsonObj.getOrNull("id")?.let { id = it.asString }
    jsonObj.getOrNull("author")?.let { author = it.asString }
    jsonObj.getOrNull("downloads")?.let { downloads = it.asLong }
    jsonObj.getOrNull("likes")?.let { likes = it.asLong }
    jsonObj.getOrNull("lastModified")?.let { lastModified = it.asString }

    jsonObj
      .getAsJsonArray("tags")
      ?.filterNot { it.isJsonNull }
      ?.forEach { tag -> tags += tag.asString }

    // No pipeline_tag field in the proto: fold it into tags (deduped) so type resolution sees it.
    jsonObj.getOrNull("pipeline_tag")?.asString?.let { pipelineTag ->
      if (pipelineTag !in tags) tags += pipelineTag
    }

    // For each file (sibling) in the model, add a sibling proto to the model proto.
    jsonObj
      .getAsJsonArray("siblings")
      ?.filter { it.isJsonObject }
      ?.mapNotNull { parseHfSiblingProto(it.asJsonObject) }
      ?.forEach { sibling -> siblings += sibling }
  }

  private fun parseHfSiblingProto(sibObj: JsonObject): HfSiblingProto? {
    val filename = sibObj.getOrNull("rfilename")?.asString
    if (filename.isNullOrEmpty()) return null

    return hfSiblingProto {
      rfilename = filename
      parseSiblingFileSize(sibObj)?.let { size = it }
    }
  }

  private fun parseSiblingFileSize(sibObj: JsonObject): Long? {
    sibObj.getOrNull("size")?.asLong?.let {
      return it
    }

    val lfsObj = sibObj.getOrNull("lfs")?.takeIf { it.isJsonObject }?.asJsonObject
    return lfsObj?.getOrNull("size")?.asLong
  }

  private fun JsonObject.getOrNull(member: String) =
    if (has(member) && !get(member).isJsonNull) get(member) else null

  companion object {
    /** Checks if the given URL belongs to Hugging Face (host contains "huggingface.co"). */
    fun isHuggingFaceUrl(url: String): Boolean {
      val trimmed = url.trim()
      if (trimmed.isEmpty()) return false
      val uri = (if (trimmed.contains("://")) trimmed else "https://$trimmed").toUri()
      val host = uri.host?.lowercase()
      return host == "huggingface.co" || host?.endsWith(".huggingface.co") == true
    }
  }
}
