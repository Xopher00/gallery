// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

private fun schema(literal: String) = Json.parseToJsonElement(literal).jsonObject

private fun assertValid(result: SchemaValidation) {
  assertTrue("expected Valid, got: $result", result is SchemaValidation.Valid)
}

private fun assertInvalid(result: SchemaValidation) {
  assertTrue("expected Invalid, got: $result", result is SchemaValidation.Invalid)
}

class StructuredOutputValidatorTest {

  @Test
  fun plainObjectMatchingSchemaIsValid() {
    val s =
        schema(
            """
            {"type":"object","required":["name"],"properties":{"name":{"type":"string"}}}
            """
                .trimIndent()
        )
    assertValid(validateAgainstSchema("""{"name":"a"}""", s))
  }

  @Test
  fun notJsonIsInvalid() {
    assertInvalid(validateAgainstSchema("not json at all", schema("""{"type":"object"}""")))
  }

  @Test
  fun wrongTopLevelTypeIsInvalid() {
    assertInvalid(validateAgainstSchema("[1,2,3]", schema("""{"type":"object"}""")))
  }

  @Test
  fun missingRequiredKeyIsInvalid() {
    assertInvalid(
        validateAgainstSchema("{}", schema("""{"type":"object","required":["name"]}"""))
    )
  }

  @Test
  fun wrongPropertyTypeIsInvalid() {
    val s =
        schema("""{"type":"object","properties":{"age":{"type":"number"}}}""")
    assertInvalid(validateAgainstSchema("""{"age":"old"}""", s))
  }

  @Test
  fun integerTypeAcceptsWholeNumberFloat() {
    val s = schema("""{"type":"object","properties":{"count":{"type":"integer"}}}""")
    assertValid(validateAgainstSchema("""{"count":5.0}""", s))
  }

  @Test
  fun integerTypeRejectsFractionalNumber() {
    val s = schema("""{"type":"object","properties":{"count":{"type":"integer"}}}""")
    assertInvalid(validateAgainstSchema("""{"count":5.5}""", s))
  }

  @Test
  fun nestedObjectPropertyIsValidatedRecursively() {
    val s =
        schema("""{"type":"object","properties":{"addr":{"type":"object","required":["city"]}}}""")
    assertInvalid(validateAgainstSchema("""{"addr":{}}""", s))
  }

  @Test
  fun nestedObjectPropertySatisfyingItsOwnSchemaIsValid() {
    val s =
        schema("""{"type":"object","properties":{"addr":{"type":"object","required":["city"]}}}""")
    assertValid(validateAgainstSchema("""{"addr":{"city":"x"}}""", s))
  }

  @Test
  fun arrayItemsAreValidatedRecursively() {
    assertInvalid(
        validateAgainstSchema("[1,2]", schema("""{"type":"array","items":{"type":"string"}}"""))
    )
  }

  @Test
  fun arrayItemsAllMatchingSchemaIsValid() {
    assertValid(
        validateAgainstSchema("""["a","b"]""", schema("""{"type":"array","items":{"type":"string"}}"""))
    )
  }

  @Test
  fun enumRejectsValueNotInList() {
    assertInvalid(
        validateAgainstSchema("\"c\"", schema("""{"type":"string","enum":["a","b"]}"""))
    )
  }

  @Test
  fun enumAcceptsListedValue() {
    assertValid(validateAgainstSchema("\"a\"", schema("""{"type":"string","enum":["a","b"]}""")))
  }

  @Test
  fun additionalPropertiesFalseRejectsUnknownKey() {
    val s =
        schema(
            """
            {"type":"object","properties":{"name":{"type":"string"}},"additionalProperties":false}
            """
                .trimIndent()
        )
    assertInvalid(validateAgainstSchema("""{"name":"a","extra":1}""", s))
  }

  @Test
  fun additionalPropertiesFalseAllowsOnlyDeclaredKeys() {
    val s =
        schema(
            """
            {"type":"object","properties":{"name":{"type":"string"}},"additionalProperties":false}
            """
                .trimIndent()
        )
    assertValid(validateAgainstSchema("""{"name":"a"}""", s))
  }

  @Test
  fun unrecognizedSchemaKeyIsIgnored() {
    assertValid(validateAgainstSchema("{}", schema("""{"type":"object","format":"email"}""")))
  }
}
