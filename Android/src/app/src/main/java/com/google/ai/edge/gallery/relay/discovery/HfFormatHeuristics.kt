/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 */

package com.google.ai.edge.gallery.relay.discovery

import com.google.ai.edge.gallery.huggingface.hasCompatibleModelFiles
import com.google.ai.edge.gallery.proto.HfModelItemProto

private val GGUF_SPLIT_PATTERN = Regex("""-\d+-of-\d+\.gguf$""")
private val MMPROJ_PATTERN = Regex("""(^|/)mmproj[^/]*\.gguf$""", RegexOption.IGNORE_CASE)

fun isGgufFileName(filename: String): Boolean = filename.lowercase().endsWith(".gguf")

// Split archives excluded: import downloads a single file by URL and cannot assemble parts.
fun isSingleFileGguf(filename: String): Boolean =
  isGgufFileName(filename) && !GGUF_SPLIT_PATTERN.containsMatchIn(filename.lowercase())

fun HfModelItemProto.hasGgufFiles(): Boolean {
  return siblingsList.any { isSingleFileGguf(it.rfilename) }
}

fun HfModelItemProto.getGgufFiles(): List<String> {
  return siblingsList.map { it.rfilename }.filter { isSingleFileGguf(it) }
}

// llama.cpp requires this exact sibling for vision, so it can't be misdeclared like a tag can.
fun HfModelItemProto.hasMmprojFile(): Boolean {
  return siblingsList.any { MMPROJ_PATTERN.containsMatchIn(it.rfilename) }
}

enum class HfModelFormat {
  LITERT,
  GGUF,
}

fun HfModelItemProto.primaryFormat(): HfModelFormat? =
  when {
    hasCompatibleModelFiles() -> HfModelFormat.LITERT
    hasGgufFiles() -> HfModelFormat.GGUF
    else -> null
  }
