// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextOverflowMessageTest {

  @Test
  fun llamaCppContextSizeReachedIsOverflow() {
    assertTrue(isContextOverflow("context size reached"))
  }

  @Test
  fun llamaCppMessageWrappedInJniExceptionIsOverflow() {
    assertTrue(isContextOverflow("java.lang.RuntimeException: context size reached"))
  }

  @Test
  fun liteRtLmTooLongMessageIsOverflow() {
    assertTrue(isContextOverflow("Input token ids are too long"))
  }

  @Test
  fun liteRtLmExceedingMaxTokensMessageIsOverflow() {
    assertTrue(isContextOverflow("Exceeding the maximum number of tokens allowed"))
  }

  @Test
  fun nullMessageIsNotOverflow() {
    assertFalse(isContextOverflow(null))
  }

  @Test
  fun emptyMessageIsNotOverflow() {
    assertFalse(isContextOverflow(""))
  }

  @Test
  fun unrelatedLlamaDecodeFailureIsNotOverflow() {
    assertFalse(isContextOverflow("llama_decode() failed"))
  }

  @Test
  fun unrelatedModelNotLoadedIsNotOverflow() {
    assertFalse(isContextOverflow("Model not loaded"))
  }
}
