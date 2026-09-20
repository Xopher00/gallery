// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportedModelStoreMemoryEstimateTest {

  @Test
  fun twoPointFourGbAllowlistModelEstimates8Gb() {
    assertEquals(8, estimatedMinDeviceMemoryInGb(2_583_085_056L))
  }

  @Test
  fun smallFileFloorsAt4Gb() {
    assertEquals(4, estimatedMinDeviceMemoryInGb(584_417_280L))
  }

  @Test
  fun threePointFourGbAllowlistModelEstimates11Gb() {
    assertEquals(11, estimatedMinDeviceMemoryInGb(3_654_467_584L))
  }
}
