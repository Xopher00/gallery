// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Fair two-mode gate: shared acquire barges and fails fast under saturation; exclusive acquire
// drains all permits single-flight, modeled on ventouxlabs/relais's RelaisAdmissionGate.
class AdmissionGate(private val capacity: Int) {

  init {
    require(capacity > 0) { "admission gate capacity must be > 0, was $capacity" }
  }

  private val sem = Semaphore(capacity, /* fair = */ true)
  private val exclusiveInFlight = AtomicBoolean(false)

  fun tryAcquireShared(): Boolean = sem.tryAcquire()

  fun releaseShared() = sem.release()

  fun tryAcquireExclusive(timeoutMs: Long): Boolean {
    if (!exclusiveInFlight.compareAndSet(false, true)) return false
    val acquired =
      try {
        sem.tryAcquire(capacity, timeoutMs, TimeUnit.MILLISECONDS)
      } catch (t: Throwable) {
        exclusiveInFlight.set(false)
        throw t
      }
    if (!acquired) exclusiveInFlight.set(false)
    return acquired
  }

  fun releaseExclusive() {
    if (exclusiveInFlight.compareAndSet(true, false)) sem.release(capacity)
  }

  fun isHeldExclusively(): Boolean = exclusiveInFlight.get()

  fun inFlightDepth(): Int = capacity - sem.availablePermits()
}
