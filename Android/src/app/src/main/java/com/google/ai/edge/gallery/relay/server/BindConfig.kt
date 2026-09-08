// Copyright 2026 Google LLC. SPDX-License-Identifier: Apache-2.0

package com.google.ai.edge.gallery.relay.server

import android.util.Log
import com.google.ai.edge.gallery.relay.server.ServerRuntime.BindMode
import com.google.ai.edge.gallery.relay.server.ServerRuntime.InterfaceInfo
import java.net.Inet4Address
import java.net.NetworkInterface

// Pure bind-host computation, no persistence/state -- ServerRuntime owns bindError/
// boundInterfaceAddress and calls these with its current in-memory mode/interface values.
object BindConfig {
    private const val TAG = "AGBindConfig"

    // INTERFACE mode throws rather than widening the bind scope when the selected interface is
    // absent or has no IPv4 -- never falls back to 0.0.0.0.
    fun bindHost(mode: BindMode, ifaceName: String?): String = when (mode) {
        BindMode.LOOPBACK -> "127.0.0.1"
        BindMode.LAN -> "0.0.0.0"
        BindMode.INTERFACE -> {
            val error = validateBindReady(mode, ifaceName)
            if (error != null) throw IllegalStateException(error)
            resolveInterfaceAddress(ifaceName!!)!!
        }
    }

    fun listAvailableInterfaces(): List<InterfaceInfo> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .mapNotNull { iface ->
                val ipv4 = iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull()
                    ?.hostAddress
                ipv4?.let { InterfaceInfo(name = iface.name, displayName = iface.displayName ?: iface.name, ipv4 = it) }
            }
            .toList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to enumerate network interfaces", e)
        emptyList()
    }

    private fun resolveInterfaceAddress(name: String): String? = try {
        NetworkInterface.getByName(name)
            ?.takeIf { it.isUp }
            ?.inetAddresses?.asSequence()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()
            ?.hostAddress
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve interface '$name'", e)
        null
    }

    fun validateBindReady(mode: BindMode, ifaceName: String?): String? {
        if (mode != BindMode.INTERFACE) return null
        if (ifaceName.isNullOrBlank()) {
            return "No network interface selected. Pick one before starting the server."
        }
        val addr = resolveInterfaceAddress(ifaceName)
        return if (addr == null) {
            "Interface '$ifaceName' is not available or has no IPv4 address right now " +
                "(disconnected, or its address changed). The server will NOT bind to 0.0.0.0 " +
                "automatically -- reconnect '$ifaceName' or choose a different interface."
        } else {
            null
        }
    }

    /** Null if INTERFACE mode is still bound to [boundAddress]; an error message if not. */
    fun checkInterfaceDrift(mode: BindMode, ifaceName: String?, boundAddress: String?): String? {
        if (mode != BindMode.INTERFACE) return null
        if (ifaceName.isNullOrBlank() || boundAddress == null) return null
        val current = resolveInterfaceAddress(ifaceName)
        return when {
            current == null -> "Interface '$ifaceName' disconnected -- the server is still bound " +
                "to its old address ($boundAddress), which no longer exists. Restart the server."
            current != boundAddress -> "Interface '$ifaceName' changed address " +
                "($boundAddress -> $current) -- restart the server to rebind to the new address."
            else -> null
        }
    }
}
