// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BearerTokenParsingTest {

    @Test
    fun nullHeaderReturnsNull() {
        assertNull(extractBearerToken(null))
    }

    @Test
    fun schemeOnlyWithNoTokenReturnsNull() {
        assertNull(extractBearerToken("Bearer"))
    }

    @Test
    fun leadingSpaceBeforeSchemeReturnsNull() {
        assertNull(extractBearerToken(" Bearer k"))
    }

    @Test
    fun schemeWithTrailingSpaceAndNoTokenReturnsNull() {
        assertNull(extractBearerToken("Bearer "))
    }

    @Test
    fun nonBearerSchemeReturnsNull() {
        assertNull(extractBearerToken("Basic k"))
    }

    @Test
    fun bearerWithTokenReturnsToken() {
        assertEquals("k", extractBearerToken("Bearer k"))
    }

    @Test
    fun lowerCaseBearerSchemeReturnsToken() {
        assertEquals("k", extractBearerToken("bearer k"))
    }

    @Test
    fun bearerWithExtraWhitespaceAroundTokenReturnsTrimmedToken() {
        assertEquals("k", extractBearerToken("Bearer   k  "))
    }
}
