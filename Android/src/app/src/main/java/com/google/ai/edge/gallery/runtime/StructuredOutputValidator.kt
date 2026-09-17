package com.google.ai.edge.gallery.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal sealed class SchemaValidation {
  data object Valid : SchemaValidation()

  data class Invalid(val reason: String) : SchemaValidation()
}

internal fun validateAgainstSchema(text: String, schema: JsonObject): SchemaValidation {
  val parsed =
    try {
      Json.parseToJsonElement(text)
    } catch (e: Exception) {
      return SchemaValidation.Invalid("not valid JSON: ${e.message}")
    }
  return validateElement(parsed, schema, path = "$")
}

internal fun validateElement(element: JsonElement, schema: JsonObject, path: String): SchemaValidation {
  (schema["type"] as? JsonPrimitive)?.let { typePrimitive ->
    val expected = typePrimitive.jsonPrimitive.content
    val matches =
      when (expected) {
        "object" -> element is JsonObject
        "array" -> element is JsonArray
        "string" -> element is JsonPrimitive && element.isString
        "number" -> element is JsonPrimitive && isNumber(element)
        "integer" ->
          element is JsonPrimitive &&
            isNumber(element) &&
            element.content.toBigDecimalOrNull()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
        "boolean" -> element is JsonPrimitive && !element.isString && (element.content == "true" || element.content == "false")
        "null" -> element is JsonPrimitive && !element.isString && element.content == "null"
        else -> true
      }
    if (!matches) {
      return SchemaValidation.Invalid("$path: expected $expected, got ${describe(element)}")
    }
  }

  (schema["enum"] as? JsonArray)?.let { allowed ->
    if (element !in allowed) {
      return SchemaValidation.Invalid("$path: must be one of ${allowed.joinToString { it.toString() }}")
    }
  }

  if (element is JsonObject) {
    (schema["required"] as? JsonArray)?.let { required ->
      val firstMissing =
        required.mapNotNull { (it as? JsonPrimitive)?.content }.firstOrNull { it !in element }
      if (firstMissing != null) {
        return SchemaValidation.Invalid("$path: missing required key \"$firstMissing\"")
      }
    }

    val properties = schema["properties"] as? JsonObject
    properties?.forEach { (key, nestedSchema) ->
      val child = element[key]
      if (child != null && nestedSchema is JsonObject) {
        val result = validateElement(child, nestedSchema, "$path.$key")
        if (result is SchemaValidation.Invalid) {
          return result
        }
      }
    }

    if ((schema["additionalProperties"] as? JsonPrimitive)?.content == "false") {
      val knownKeys = properties?.keys ?: emptySet()
      val firstUnexpected = element.keys.firstOrNull { it !in knownKeys }
      if (firstUnexpected != null) {
        return SchemaValidation.Invalid("$path: unexpected key \"$firstUnexpected\"")
      }
    }
  } else if (element is JsonArray) {
    (schema["items"] as? JsonObject)?.let { itemSchema ->
      element.forEachIndexed { index, item ->
        val result = validateElement(item, itemSchema, "$path[$index]")
        if (result is SchemaValidation.Invalid) {
          return result
        }
      }
    }
  }

  return SchemaValidation.Valid
}

private fun isNumber(element: JsonPrimitive): Boolean = element.content.toBigDecimalOrNull() != null

private fun describe(element: JsonElement): String =
  when (element) {
    is JsonObject -> "object"
    is JsonArray -> "array"
    is JsonPrimitive ->
      when {
        element.isString -> "string"
        element.content == "null" -> "null"
        else -> "number"
      }
  }
