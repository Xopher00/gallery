/*
 * Box: runtime-level token accounting, below the UI and the API server. Counts come from
 * engine-internal state that no layer above the engine can observe; front ends only read back.
 */

package com.google.ai.edge.gallery.relay.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Whether a count came from the engine itself or from a heuristic. */
enum class CountKind {
  EXACT,
  ESTIMATED,
}

data class TokenCount(val tokens: Int, val kind: CountKind) {
  companion object {
    val ZERO_EXACT = TokenCount(0, CountKind.EXACT)
  }
}

/** [exactTotal] is set when an engine measures a turn more precisely than it can split it. */
data class TurnTokenUsage(
  val prompt: TokenCount,
  val completion: TokenCount,
  val exactTotal: Int? = null,
  /** Monotonic per-model turn number, for detecting a stale or concurrent read. */
  val turnSequence: Long = 0L,
) {
  val total: Int
    get() = exactTotal ?: (prompt.tokens + completion.tokens)

  val isFullyExact: Boolean
    get() = prompt.kind == CountKind.EXACT && completion.kind == CountKind.EXACT

  operator fun plus(other: TurnTokenUsage): TurnTokenUsage =
    TurnTokenUsage(
      prompt = TokenCount(prompt.tokens + other.prompt.tokens, weakest(prompt, other.prompt)),
      completion =
        TokenCount(
          completion.tokens + other.completion.tokens,
          weakest(completion, other.completion),
        ),
      exactTotal = exactTotal?.let { a -> other.exactTotal?.let { b -> a + b } },
      turnSequence = maxOf(turnSequence, other.turnSequence),
    )

  private fun weakest(a: TokenCount, b: TokenCount): CountKind =
    if (a.kind == CountKind.EXACT && b.kind == CountKind.EXACT) CountKind.EXACT
    else CountKind.ESTIMATED
}

/**
 * Last-turn usage per model, written by engines, read by any front end. Relies on inference being
 * single-flight per model; [TurnTokenUsage.turnSequence] is the tripwire if that ever breaks.
 */
object TurnUsageStore {
  private val usage = ConcurrentHashMap<String, TurnTokenUsage>()
  private val sequence = ConcurrentHashMap<String, Long>()
  // Per-model, not a set: chat and API turns can overlap on one model.
  private val inFlight = ConcurrentHashMap<String, AtomicInteger>()

  /** Clears the previous turn's numbers, opens the turn, and returns this turn's sequence number. */
  fun begin(modelName: String): Long {
    usage.remove(modelName)
    inFlight.computeIfAbsent(modelName) { AtomicInteger(0) }.incrementAndGet()
    return sequence.merge(modelName, 1L, Long::plus) ?: 1L
  }

  fun record(modelName: String, turnUsage: TurnTokenUsage) {
    usage[modelName] = turnUsage
    closeTurn(modelName)
  }

  /** Closes the turn without recording usage, e.g. an error before any tokens were produced. */
  fun abort(modelName: String) {
    closeTurn(modelName)
  }

  fun isInFlight(modelName: String): Boolean = (inFlight[modelName]?.get() ?: 0) > 0

  /** Reads without consuming; null when the engine recorded nothing for this model. */
  fun peek(modelName: String): TurnTokenUsage? = usage[modelName]

  fun clear(modelName: String) {
    usage.remove(modelName)
  }

  /** Never drops below zero, so a stray extra close cannot flip isInFlight negative-then-true. */
  private fun closeTurn(modelName: String) {
    val counter = inFlight[modelName] ?: return
    if (counter.updateAndGet { c -> (c - 1).coerceAtLeast(0) } == 0) {
      inFlight.remove(modelName, counter)
    }
  }
}

/** Fallback for engines with no counter; four characters per token, always ESTIMATED. */
fun estimateTokens(text: String): TokenCount =
  TokenCount(tokens = (text.length + 3) / 4, kind = CountKind.ESTIMATED)
