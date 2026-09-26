// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfCardFactsParsingTest {

  private fun parse(json: String): HfCardFacts =
    parseHfCardFacts(JsonParser.parseString(json).asJsonObject)

  private fun parseWithCardData(cardData: String): HfCardFacts =
    parse("""{"modelId":"a/b","cardData":$cardData}""")

  @Test
  fun `base_model as a string becomes baseModel`() {
    val facts = parseWithCardData("""{"base_model":"google/gemma-3-1b-it"}""")
    assertEquals("google/gemma-3-1b-it", facts.baseModel)
  }

  @Test
  fun `base_model array takes the first element`() {
    val facts = parseWithCardData("""{"base_model":["a/b","c/d"]}""")
    assertEquals("a/b", facts.baseModel)
  }

  @Test
  fun `license other resolves to license_name`() {
    val facts = parseWithCardData("""{"license":"other","license_name":"custom-lic"}""")
    assertEquals("custom-lic", facts.license)
  }

  @Test
  fun `license without license_name is used as is`() {
    val facts = parseWithCardData("""{"license":"apache-2.0"}""")
    assertEquals("apache-2.0", facts.license)
  }

  @Test
  fun `gated variants false absent auto true`() {
    assertFalse(parse("""{"modelId":"a/b","gated":false}""").gated)
    assertFalse(parseWithCardData("{}").gated)
    assertTrue(parse("""{"modelId":"a/b","gated":"auto"}""").gated)
    assertTrue(parse("""{"modelId":"a/b","gated":true}""").gated)
  }

  @Test
  fun `missing cardData leaves every nullable field null`() {
    val facts = parse("""{"modelId":"a/b","gated":false}""")
    assertNull(facts.baseModel)
    assertNull(facts.baseModelRelation)
    assertNull(facts.license)
    assertNull(facts.pipelineTag)
    assertNull(facts.libraryName)
    assertFalse(facts.gated)
  }

  @Test
  fun `cardData member that is JSON null stays null`() {
    val facts = parseWithCardData("""{"pipeline_tag":null,"library_name":null}""")
    assertNull(facts.pipelineTag)
    assertNull(facts.libraryName)
  }

  @Test
  fun `toDescription renders quantized lineage license and capabilities`() {
    val description = HfCardFacts(
      baseModel = "google/gemma-3-1b-it",
      baseModelRelation = "quantized",
      license = "gemma",
      pipelineTag = "text-generation",
      libraryName = "transformers",
      gated = false,
    ).toDescription()
    assertEquals(
      "Quantized from google/gemma-3-1b-it, licensed under gemma. " +
        "Supports text generation, published with the transformers library.",
      description,
    )
  }

  @Test
  fun `toDescription without relation starts with From base`() {
    val description = HfCardFacts(
      baseModel = "google/gemma-3-1b-it",
      baseModelRelation = null,
      license = "gemma",
      pipelineTag = "text-generation",
      libraryName = "transformers",
      gated = false,
    ).toDescription()
    assertTrue(description.startsWith("From google/gemma-3-1b-it"))
  }

  @Test
  fun `toDescription with only gated renders gate sentence`() {
    val description = HfCardFacts(
      baseModel = null,
      baseModelRelation = null,
      license = null,
      pipelineTag = null,
      libraryName = null,
      gated = true,
    ).toDescription()
    assertEquals("Access is gated by the repository owner.", description)
  }

  @Test
  fun `toDescription with all fields null and not gated is empty`() {
    val description = HfCardFacts(
      baseModel = null,
      baseModelRelation = null,
      license = null,
      pipelineTag = null,
      libraryName = null,
      gated = false,
    ).toDescription()
    assertEquals("", description)
  }
}
