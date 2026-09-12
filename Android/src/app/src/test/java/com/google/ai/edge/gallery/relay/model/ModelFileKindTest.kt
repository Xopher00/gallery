// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals

private val EXPECTED_SECTIONS =
    mapOf(
        "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm.head" to
            listOf("TF_LITE_PREFILL_DECODE"),
        "FastVLM-0.5B.litertlm.head" to
            listOf(
                "tf_lite_embedder",
                "tf_lite_prefill_decode",
                "tf_lite_vision_adapter",
                "tf_lite_vision_encoder",
            ),
        "gemma3-1b-it-int4.litertlm.head" to listOf("TF_LITE_PREFILL_DECODE"),
        "Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm.head" to
            listOf("TF_LITE_AUX", "TF_LITE_EMBEDDER", "TF_LITE_PREFILL_DECODE"),
        "gemma-4-E2B-it-gpu.litertlm.head" to listOf("tf_lite_artisan_text_decoder"),
        "gemma-4-E2B-it.litertlm.head" to
            listOf(
                "tf_lite_audio_adapter",
                "tf_lite_audio_encoder_hw",
                "tf_lite_embedder",
                "tf_lite_end_of_audio",
                "tf_lite_end_of_vision",
                "tf_lite_mtp_drafter",
                "tf_lite_per_layer_embedder",
                "tf_lite_prefill_decode",
                "tf_lite_vision_adapter",
                "tf_lite_vision_encoder",
            ),
        "Giga-Embeddings-instruct-480M-0826_wi8fc.litertlm.head" to
            listOf("tf_lite_embedder", "tf_lite_text_encoder"),
        "giga-embeddings.litertlm.head" to listOf("tf_lite_embedder", "tf_lite_text_encoder"),
        "LFM2.5-2.6B_int4.litertlm.head" to listOf("tf_lite_prefill_decode"),
        "mobile_actions_q8_ekv1024.litertlm.head" to listOf("tf_lite_prefill_decode"),
        "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm.head" to
            listOf("TF_LITE_PREFILL_DECODE"),
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm.head" to
            listOf("TF_LITE_PREFILL_DECODE"),
        "tiny_garden_q8_ekv1024.litertlm.head" to listOf("tf_lite_prefill_decode"),
    )

private val EXPECTED_KINDS =
    mapOf(
        "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm.head" to
            ModelFileKind.CHAT,
        "FastVLM-0.5B.litertlm.head" to ModelFileKind.CHAT,
        "gemma3-1b-it-int4.litertlm.head" to ModelFileKind.CHAT,
        "Gemma3-1B-IT_q4_ekv1280_sm8650.litertlm.head" to ModelFileKind.CHAT,
        "gemma-4-E2B-it-gpu.litertlm.head" to ModelFileKind.EMBEDDING,
        "gemma-4-E2B-it.litertlm.head" to ModelFileKind.CHAT,
        "Giga-Embeddings-instruct-480M-0826_wi8fc.litertlm.head" to ModelFileKind.EMBEDDING,
        "giga-embeddings.litertlm.head" to ModelFileKind.EMBEDDING,
        "LFM2.5-2.6B_int4.litertlm.head" to ModelFileKind.CHAT,
        "mobile_actions_q8_ekv1024.litertlm.head" to ModelFileKind.CHAT,
        "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm.head" to ModelFileKind.CHAT,
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm.head" to ModelFileKind.CHAT,
        "tiny_garden_q8_ekv1024.litertlm.head" to ModelFileKind.CHAT,
    )

class ModelFileKindTest {

  private fun fixtureNames(): List<String> {
    val dir =
        File(javaClass.classLoader!!.getResource("litertlm-headers")!!.toURI())
    return dir.list()!!.sorted()
  }

  private fun copyFixtureToTempFile(name: String): File {
    val tempFile = File.createTempFile("litertlm-header-", ".litertlm")
    tempFile.deleteOnExit()
    javaClass.classLoader!!.getResourceAsStream("litertlm-headers/$name")!!.use { input ->
      tempFile.outputStream().use { output -> input.copyTo(output) }
    }
    return tempFile
  }

  @Test
  fun sectionTypesMatchTheFile() {
    for (name in fixtureNames()) {
      val expected = EXPECTED_SECTIONS[name] ?: error("no expected sections for fixture $name")
      val tempFile = copyFixtureToTempFile(name)
      val actual = probeLiteRtLmModelTypes(tempFile.path).sorted().distinct()
      assertEquals("sections for $name", expected, actual)
    }
  }

  @Test
  fun kindMatchesTheRule() {
    for (name in fixtureNames()) {
      val expected = EXPECTED_KINDS[name] ?: error("no expected kind for fixture $name")
      val tempFile = copyFixtureToTempFile(name)
      val actual = probeModelFileKind(tempFile.path)
      assertEquals("kind for $name", expected, actual)
    }
  }
}
