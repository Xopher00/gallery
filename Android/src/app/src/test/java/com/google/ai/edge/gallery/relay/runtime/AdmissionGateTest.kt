// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionGateTest {

  @Test(expected = IllegalArgumentException::class)
  fun nonPositiveCapacityThrows() {
    AdmissionGate(capacity = 0)
  }

  @Test
  fun negativeCapacityAlsoThrows() {
    try {
      AdmissionGate(capacity = -1)
      throw AssertionError("expected IllegalArgumentException")
    } catch (expected: IllegalArgumentException) {
      // Expected: capacity must be > 0.
    }
  }

  @Test
  fun sharedAdmitsUpToCapacityThenFailsFast() {
    val gate = AdmissionGate(capacity = 2)
    assertTrue(gate.tryAcquireShared())
    assertTrue(gate.tryAcquireShared())
    assertFalse(gate.tryAcquireShared())
    assertEquals(2, gate.inFlightDepth())
  }

  @Test
  fun releasedSharedPermitAdmitsOneMoreCaller() {
    val gate = AdmissionGate(capacity = 2)
    gate.tryAcquireShared()
    gate.tryAcquireShared()
    assertFalse(gate.tryAcquireShared())
    gate.releaseShared()
    assertTrue(gate.tryAcquireShared())
  }

  @Test
  fun exclusiveNeedsAllPermitsFree() {
    val gate = AdmissionGate(capacity = 2)
    assertTrue(gate.tryAcquireShared())
    // One permit free, one held: exclusive mode needs ALL permits, so it fails immediately.
    assertFalse(gate.tryAcquireExclusive(timeoutMs = 0))
    gate.releaseShared()
    assertTrue(gate.tryAcquireExclusive(timeoutMs = 0))
    assertTrue(gate.isHeldExclusively())
  }

  @Test
  fun secondExclusiveAcquireFailsFastWhileFirstIsHeld() {
    val gate = AdmissionGate(capacity = 2)
    assertTrue(gate.tryAcquireExclusive(timeoutMs = 0))
    val startedAt = System.nanoTime()
    assertFalse(gate.tryAcquireExclusive(timeoutMs = 10_000))
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    // Single-flight path returns without waiting out the timeout.
    assertTrue("second exclusive acquire took ${elapsedMs}ms, expected fast-fail", elapsedMs < 1_000)
    gate.releaseExclusive()
  }

  @Test
  fun releaseExclusiveReturnsDepthToZero() {
    val gate = AdmissionGate(capacity = 2)
    assertTrue(gate.tryAcquireExclusive(timeoutMs = 0))
    assertEquals(2, gate.inFlightDepth())
    gate.releaseExclusive()
    assertEquals(0, gate.inFlightDepth())
    assertFalse(gate.isHeldExclusively())
  }
}
