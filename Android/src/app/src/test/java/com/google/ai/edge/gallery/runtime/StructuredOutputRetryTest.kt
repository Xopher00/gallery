// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val SCHEMA = Json.parseToJsonElement("""{"type":"object","required":["ok"]}""").jsonObject

private const val PROMPT = "Return a greeting as JSON"

private const val LAST_REPLY = """{"greeting": "hi"}"""

private const val VALID_REPLY = """{"ok":true}"""

private const val INVALID_REPLY = "not json"

/** Canned LLM: pops the next reply per call and records every prompt it received. */
private class ScriptedLlm(replies: List<String>) {
  private val nextReply = ArrayDeque(replies)
  val prompts = mutableListOf<String>()
  val callCount: Int
    get() = prompts.size

  suspend fun runOnce(prompt: String): String {
    prompts.add(prompt)
    return if (nextReply.size > 1) nextReply.removeFirst() else nextReply.first()
  }
}

class StructuredOutputRetryTest {

  // Part A: decideStructuredOutputRetry

  @Test
  fun validReplyIsAccepted() {
    val decision = decideStructuredOutputRetry(PROMPT, VALID_REPLY, SchemaValidation.Valid, 0)
    assertEquals(StructuredOutputDecision.Accept, decision)
  }

  @Test
  fun validReplyIsAcceptedEvenAtMaxAttempt() {
    val decision =
        decideStructuredOutputRetry(
            PROMPT, VALID_REPLY, SchemaValidation.Valid, MAX_STRUCTURED_OUTPUT_RETRIES)
    assertEquals(StructuredOutputDecision.Accept, decision)
  }

  @Test
  fun invalidReplyBelowMaxRetriesReturnsRetry() {
    val decision =
        decideStructuredOutputRetry(
            PROMPT, LAST_REPLY, SchemaValidation.Invalid("missing key x"), 0)
    val retry = decision as StructuredOutputDecision.Retry
    assertTrue(retry.repairPrompt.contains(PROMPT))
    assertTrue(retry.repairPrompt.contains("missing key x"))
    assertTrue(retry.repairPrompt.contains(LAST_REPLY))
  }

  @Test
  fun invalidReplyAtMaxRetriesReturnsGiveUp() {
    val decision =
        decideStructuredOutputRetry(
            PROMPT, INVALID_REPLY, SchemaValidation.Invalid("still wrong"),
            MAX_STRUCTURED_OUTPUT_RETRIES)
    assertEquals(StructuredOutputDecision.GiveUp(reason = "still wrong"), decision)
  }

  @Test
  fun invalidReplyOneBelowMaxStillRetries() {
    val decision =
        decideStructuredOutputRetry(
            PROMPT, INVALID_REPLY, SchemaValidation.Invalid("still wrong"),
            MAX_STRUCTURED_OUTPUT_RETRIES - 1)
    assertEquals(StructuredOutputDecision.Retry::class, decision::class)
  }

  // Part B: runWithStructuredOutputRetry

  @Test
  fun acceptsOnFirstValidReply() = runBlocking {
    val llm = ScriptedLlm(listOf(VALID_REPLY))
    val outcome = runWithStructuredOutputRetry(PROMPT, SCHEMA, llm::runOnce)
    assertEquals(StructuredOutputOutcome.Accepted(VALID_REPLY), outcome)
    assertEquals(1, llm.callCount)
  }

  @Test
  fun retriesOnceThenAccepts() = runBlocking {
    val llm = ScriptedLlm(listOf(INVALID_REPLY, VALID_REPLY))
    val outcome = runWithStructuredOutputRetry(PROMPT, SCHEMA, llm::runOnce)
    assertEquals(StructuredOutputOutcome.Accepted(VALID_REPLY), outcome)
    assertEquals(2, llm.callCount)
    assertEquals(PROMPT, llm.prompts[0])
    assertTrue(llm.prompts[1] != PROMPT)
  }

  @Test
  fun exhaustsAfterMaxRetries() = runBlocking {
    val llm = ScriptedLlm(listOf(INVALID_REPLY))
    val outcome = runWithStructuredOutputRetry(PROMPT, SCHEMA, llm::runOnce)
    val exhausted = outcome as StructuredOutputOutcome.Exhausted
    assertEquals(INVALID_REPLY, exhausted.lastOutput)
    assertTrue(exhausted.reason.contains("not valid JSON"))
    assertEquals(MAX_STRUCTURED_OUTPUT_RETRIES + 1, llm.callCount)
    assertEquals(PROMPT, llm.prompts[0])
    for (prompt in llm.prompts.drop(1)) {
      assertTrue(prompt != PROMPT)
    }
  }
}
