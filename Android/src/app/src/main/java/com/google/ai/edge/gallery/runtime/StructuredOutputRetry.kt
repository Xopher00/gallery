package com.google.ai.edge.gallery.runtime

import kotlinx.serialization.json.JsonObject

const val MAX_STRUCTURED_OUTPUT_RETRIES = 2

sealed class StructuredOutputOutcome {
  data class Accepted(val text: String) : StructuredOutputOutcome()
  data class Exhausted(val lastOutput: String, val reason: String) : StructuredOutputOutcome()
}

internal sealed class StructuredOutputDecision {
  data object Accept : StructuredOutputDecision()
  data class Retry(val repairPrompt: String) : StructuredOutputDecision()
  data class GiveUp(val reason: String) : StructuredOutputDecision()
}

internal fun decideStructuredOutputRetry(
  originalPrompt: String,
  lastText: String,
  validation: SchemaValidation,
  attempt: Int,
): StructuredOutputDecision {
  if (validation is SchemaValidation.Valid) return StructuredOutputDecision.Accept
  val reason = (validation as SchemaValidation.Invalid).reason
  if (attempt >= MAX_STRUCTURED_OUTPUT_RETRIES) return StructuredOutputDecision.GiveUp(reason)
  val repairPrompt =
    "$originalPrompt\n\nYour previous reply was:\n$lastText\n\nThat reply was invalid: $reason\n" +
      "Reply again with ONLY the corrected JSON, no other text."
  return StructuredOutputDecision.Retry(repairPrompt)
}

// runOnce is a callback so this file stays free of any dependency on relay.server.handlers or
// ktor; any inference call site (HTTP, agent, in-app chat) can supply its own collector.
suspend fun runWithStructuredOutputRetry(
  initialPrompt: String,
  schema: JsonObject,
  runOnce: suspend (prompt: String) -> String,
): StructuredOutputOutcome {
  var text = runOnce(initialPrompt)
  var attempt = 0
  while (true) {
    val validation = validateAgainstSchema(text, schema)
    when (val decision = decideStructuredOutputRetry(initialPrompt, text, validation, attempt)) {
      StructuredOutputDecision.Accept -> return StructuredOutputOutcome.Accepted(text)
      is StructuredOutputDecision.GiveUp ->
        return StructuredOutputOutcome.Exhausted(lastOutput = text, reason = decision.reason)
      is StructuredOutputDecision.Retry -> {
        attempt++
        text = runOnce(decision.repairPrompt)
      }
    }
  }
}
