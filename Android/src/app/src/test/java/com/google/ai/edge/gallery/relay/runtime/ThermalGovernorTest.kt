// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGovernorTest {

  @Test
  fun severeShedsEvenWithGoodHeadroomAndSpeed() {
    assertTrue(
      ThermalGovernor.shouldShed(
        status = PowerManager.THERMAL_STATUS_SEVERE,
        headroom = 0.1f,
        decodeAverage = 20.0,
      )
    )
  }

  @Test
  fun emergencyAlsoShedsAsSevereOrHigher() {
    assertTrue(
      ThermalGovernor.shouldShed(
        status = PowerManager.THERMAL_STATUS_EMERGENCY,
        headroom = 0.1f,
        decodeAverage = 20.0,
      )
    )
  }

  @Test
  fun headroomAtNinetyFivePercentSheds() {
    assertTrue(
      ThermalGovernor.shouldShed(
        status = PowerManager.THERMAL_STATUS_NONE,
        headroom = 0.95f,
        decodeAverage = 20.0,
      )
    )
  }

  @Test
  fun headroomJustBelowThresholdDoesNotShed() {
    assertFalse(
      ThermalGovernor.shouldShed(
        status = PowerManager.THERMAL_STATUS_NONE,
        headroom = 0.94f,
        decodeAverage = 20.0,
      )
    )
  }

  @Test
  fun decodeAverageBelowFloorSheds() {
    assertTrue(
      ThermalGovernor.shouldShed(
        status = PowerManager.THERMAL_STATUS_NONE,
        headroom = 0.1f,
        decodeAverage = 2.9,
      )
    )
  }

  @Test
  fun coolDeviceWithGoodSpeedDoesNotShed() {
    assertFalse(
      ThermalGovernor.shouldShed(
        status = PowerManager.THERMAL_STATUS_NONE,
        headroom = 0.1f,
        decodeAverage = 20.0,
      )
    )
  }

  @Test
  fun decodeFloorNeedsThreeSamplesBeforeItApplies() {
    val tracker = ThermalGovernor.DecodeRateTracker()
    tracker.record(1.0)
    assertNull(tracker.average())
    tracker.record(1.0)
    assertNull(tracker.average())
    tracker.record(1.0)
    assertEquals(1.0, tracker.average())
  }

  @Test
  fun moderateStatusRequestsTheStartDelay() {
    assertTrue(ThermalGovernor.shouldDelay(PowerManager.THERMAL_STATUS_MODERATE))
    assertEquals(1_500L, ThermalGovernor.MODERATE_START_DELAY_MS)
  }

  @Test
  fun nonModerateStatusesDoNotRequestTheStartDelay() {
    assertFalse(ThermalGovernor.shouldDelay(PowerManager.THERMAL_STATUS_NONE))
    assertFalse(ThermalGovernor.shouldDelay(PowerManager.THERMAL_STATUS_SEVERE))
  }

  @Test
  fun criticalStopsARunningGeneration() {
    assertTrue(ThermalGovernor.shouldStop(PowerManager.THERMAL_STATUS_CRITICAL))
  }

  @Test
  fun belowCriticalDoesNotStopARunningGeneration() {
    assertFalse(ThermalGovernor.shouldStop(PowerManager.THERMAL_STATUS_SEVERE))
  }

  @Test
  fun retryAfterIsThirtySecondsAtCriticalOrAbove() {
    assertEquals(30, ThermalGovernor.retryAfterSeconds(PowerManager.THERMAL_STATUS_CRITICAL))
    assertEquals(30, ThermalGovernor.retryAfterSeconds(PowerManager.THERMAL_STATUS_EMERGENCY))
  }

  @Test
  fun retryAfterIsTenSecondsAtSevere() {
    assertEquals(10, ThermalGovernor.retryAfterSeconds(PowerManager.THERMAL_STATUS_SEVERE))
  }

  @Test
  fun retryAfterIsEightSecondsOtherwise() {
    assertEquals(8, ThermalGovernor.retryAfterSeconds(PowerManager.THERMAL_STATUS_NONE))
    assertEquals(8, ThermalGovernor.retryAfterSeconds(PowerManager.THERMAL_STATUS_MODERATE))
  }
}
