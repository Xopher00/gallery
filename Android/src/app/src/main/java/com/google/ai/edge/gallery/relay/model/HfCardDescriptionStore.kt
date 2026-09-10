// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

private const val CARD_CACHE_TAG = "AGHfCardDescriptionStore"
private const val DESCRIPTIONS_FILENAME = "hf_card_descriptions.json"

/** [baseModel] is the upstream model the repo was derived from, used as the display name. */
data class HfCardEntry(val description: String, val baseModel: String? = null)

class HfCardDescriptionStore(private val modelsDir: File) {
  private val lock = Any()
  private var cache: MutableMap<String, HfCardEntry>? = null

  fun get(modelId: String): HfCardEntry? = synchronized(lock) { ensureLoadedLocked()[modelId] }

  fun put(modelId: String, entry: HfCardEntry) {
    synchronized(lock) {
      val map = ensureLoadedLocked()
      map[modelId] = entry
      saveToDiskLocked(map)
    }
  }

  private fun ensureLoadedLocked(): MutableMap<String, HfCardEntry> {
    cache?.let {
      return it
    }
    val loaded = readFromDisk()
    cache = loaded
    return loaded
  }

  // Reads sit on the model-loading path, so a corrupt or unreadable file degrades to an empty
  // cache rather than propagating.
  private fun readFromDisk(): MutableMap<String, HfCardEntry> {
    try {
      val file = File(modelsDir, DESCRIPTIONS_FILENAME)
      if (!file.exists()) return mutableMapOf()
      val type = object : TypeToken<MutableMap<String, HfCardEntry>>() {}.type
      return Gson().fromJson(file.readText(), type) ?: mutableMapOf()
    } catch (e: Exception) {
      Log.e(CARD_CACHE_TAG, "Failed to read HF card description cache from disk", e)
      return mutableMapOf()
    }
  }

  private fun saveToDiskLocked(map: Map<String, HfCardEntry>) {
    try {
      File(modelsDir, DESCRIPTIONS_FILENAME).writeText(Gson().toJson(map))
    } catch (e: Exception) {
      Log.e(CARD_CACHE_TAG, "Failed to write HF card description cache to disk", e)
    }
  }
}
