// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server

import com.google.ai.edge.gallery.relay.server.ServerRuntime.BindMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BindHostTest {

    @Test
    fun loopbackReturnsLocalAddress() {
        assertEquals("127.0.0.1", BindConfig.bindHost(BindMode.LOOPBACK, null) { "ignored" })
    }

    @Test
    fun lanReturnsWildcardAddress() {
        assertEquals("0.0.0.0", BindConfig.bindHost(BindMode.LAN, null) { "ignored" })
    }

    @Test
    fun interfaceWithBlankNameThrowsNoInterfaceSelected() {
        val error = assertThrows(IllegalStateException::class.java) {
            BindConfig.bindHost(BindMode.INTERFACE, "") { "10.0.0.1" }
        }
        assertEquals("No network interface selected. Pick one before starting the server.", error.message)
    }

    @Test
    fun interfaceWithResolvedAddressReturnsThatAddress() {
        val host = BindConfig.bindHost(BindMode.INTERFACE, "wlan0") { "10.0.0.5" }
        assertEquals("10.0.0.5", host)
    }

    @Test
    fun interfaceWithUnresolvableAddressThrowsNotAvailable() {
        val error = assertThrows(IllegalStateException::class.java) {
            BindConfig.bindHost(BindMode.INTERFACE, "wlan0") { null }
        }
        assertMessageMentionsNotAvailable(error.message)
    }

    @Test
    fun interfaceVanishingBetweenValidationAndResolveThrowsNotAvailable() {
        var calls = 0
        val error = assertThrows(IllegalStateException::class.java) {
            BindConfig.bindHost(BindMode.INTERFACE, "wlan0") { name ->
                calls++
                if (calls == 1) "10.0.0.5" else null
            }
        }
        assertMessageMentionsNotAvailable(error.message)
    }

    private fun assertMessageMentionsNotAvailable(message: String?) {
        requireNotNull(message)
        assert(message.contains("not available or has no IPv4 address")) {
            "Expected 'not available' message, got: $message"
        }
        assert(!message.contains("No network interface selected")) {
            "Expected 'not available' message, got: $message"
        }
    }
}
