/*
 * relay: this project's own code, not part of upstream google-ai-edge/gallery.
 */

package com.google.ai.edge.gallery.relay.discovery

import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.proto.HfModelItemProto

enum class HfCapability {
  TEXT,
  IMAGE,
  AUDIO,
}

private val TEXT_TAGS = setOf("text-generation", "text2text-generation", "conversational")
private val IMAGE_TAGS = setOf("image-text-to-text", "image-to-text", "visual-question-answering")
private val AUDIO_TAGS =
  setOf("automatic-speech-recognition", "audio-text-to-text", "audio-classification")

private val TAGS_BY_CAPABILITY: Map<HfCapability, Set<String>> =
  mapOf(
    HfCapability.TEXT to TEXT_TAGS,
    HfCapability.IMAGE to IMAGE_TAGS,
    HfCapability.AUDIO to AUDIO_TAGS,
  )

// IMAGE_GEN (Stable Diffusion) is a different model family than this search targets; no mapping.
fun capabilityForTaskId(taskId: String): HfCapability? =
  when (taskId) {
    BuiltInTaskId.LLM_CHAT,
    BuiltInTaskId.LLM_PROMPT_LAB,
    BuiltInTaskId.LLM_AGENT_CHAT,
    BuiltInTaskId.LLM_TINY_GARDEN,
    BuiltInTaskId.LLM_MOBILE_ACTIONS -> HfCapability.TEXT
    BuiltInTaskId.LLM_ASK_IMAGE -> HfCapability.IMAGE
    BuiltInTaskId.LLM_ASK_AUDIO,
    BuiltInTaskId.WHISPER -> HfCapability.AUDIO
    else -> null
  }

// HF pipeline tags are self-reported and frequently absent or wrong — ranking signal only, never a filter.
fun HfModelItemProto.matchesCapability(capability: HfCapability): Boolean {
  val wanted = TAGS_BY_CAPABILITY.getValue(capability)
  return tagsList.any { it in wanted }
}

/** User-facing type filter. UNKNOWN is a real bucket: pipeline_tag is missing on ~40% of repos. */
enum class HfModelType {
  VISION,
  SPEECH,
  TEXT,
  IMAGE_GEN,
  UNKNOWN,
}

// image-to-image (img2img/inpainting) shares the GGUF SD import path with text-to-image.
private val IMAGE_GEN_TAGS = setOf("text-to-image", "image-to-image")

// Loose fallback for repos with no pipeline_tag and no exact-family tag match.
private val VISION_KEYWORDS = setOf("vision", "multimodal", "vlm", "ocr")
private val SPEECH_KEYWORDS = setOf("speech", "audio", "asr", "tts", "voice")

// Order: mmproj sibling (unspoofable) > pipeline_tag family, folded into tags at parse time
// (see HuggingFaceApiClient) > loose keyword tags > UNKNOWN. Never defaults to TEXT.
fun HfModelItemProto.resolveType(): HfModelType {
  if (hasMmprojFile()) return HfModelType.VISION

  val tagSet = tagsList.map { it.lowercase() }.toSet()
  return when {
    tagSet.any { it in IMAGE_GEN_TAGS } -> HfModelType.IMAGE_GEN
    tagSet.any { it in IMAGE_TAGS } -> HfModelType.VISION
    tagSet.any { it in AUDIO_TAGS } -> HfModelType.SPEECH
    tagSet.any { it in TEXT_TAGS } -> HfModelType.TEXT
    tagSet.any { it in VISION_KEYWORDS } -> HfModelType.VISION
    tagSet.any { it in SPEECH_KEYWORDS } -> HfModelType.SPEECH
    else -> HfModelType.UNKNOWN
  }
}
