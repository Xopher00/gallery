// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private data class BareErrorFinding(val file: String, val line: Int)

private fun findBareErrorBodies(fileName: String, source: String): List<BareErrorFinding> {
  val findings = mutableListOf<BareErrorFinding>()
  var searchFrom = 0
  while (true) {
    val callStart = source.indexOf("call.respond(", searchFrom)
    if (callStart == -1) break
    val argsStart = callStart + "call.respond(".length
    var depth = 1
    var i = argsStart
    while (i < source.length && depth > 0) {
      when (source[i]) {
        '(' -> depth++
        ')' -> depth--
      }
      i++
    }
    val argsEnd = i - 1
    val args = source.substring(argsStart, argsEnd)
    if (args.contains("\"error\"") && !args.contains("ErrorEnvelope")) {
      val line = source.substring(0, callStart).count { it == '\n' } + 1
      findings.add(BareErrorFinding(fileName, line))
    }
    searchFrom = argsEnd
  }
  return findings
}

class ErrorShapeScanTest {

  @Test
  fun scannerFlagsBareErrorAcrossLines() {
    val source =
        """
        call.respond(
            HttpStatusCode.BadRequest, mapOf(
            "error" to "x"))
        """
            .trimIndent()
    val findings = findBareErrorBodies("Fake.kt", source)
    assertEquals(1, findings.size)
  }

  @Test
  fun scannerAcceptsEnvelope() {
    val source = """call.respond(HttpStatusCode.BadRequest, ErrorEnvelope(ErrorBody(message = "x")))"""
    val findings = findBareErrorBodies("Fake.kt", source)
    assertEquals(emptyList<BareErrorFinding>(), findings)
  }

  @Test
  fun serverSourceHasNoBareErrorBodies() {
    val serverDir =
        File(System.getProperty("user.dir"), "src/main/java/com/google/ai/edge/gallery/relay/server")
    assertTrue("server source directory not found: ${serverDir.path}", serverDir.isDirectory)

    val kotlinFiles = serverDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    var totalRespondCalls = 0
    val allFindings = mutableListOf<BareErrorFinding>()
    for (file in kotlinFiles) {
      val text = file.readText()
      totalRespondCalls += Regex("call\\.respond\\(").findAll(text).count()
      allFindings.addAll(findBareErrorBodies(file.path, text))
    }

    assertTrue(
        "expected more than 20 call.respond( calls, found $totalRespondCalls",
        totalRespondCalls > 20,
    )
    assertEquals("bare error bodies found: $allFindings", emptyList<BareErrorFinding>(), allFindings)
  }
}
