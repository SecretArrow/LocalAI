package com.localai.runtime.core.net

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Enumerates LAN-facing IPv4 addresses of this device.
 *
 * Uses [NetworkInterface] enumeration (no location permission required, unlike
 * WifiManager connection info): non-loopback, up interfaces with site-local
 * IPv4 addresses (10/8, 172.16/12, 192.168/16) are returned.
 */
object LanAddresses {

    /**
     * The [context] parameter is kept for API compatibility with the frozen
     * contract; the implementation is context-free.
     */
    fun ipv4Addresses(context: Context): List<String> {
        val result = LinkedHashSet<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val nic = interfaces.nextElement() ?: continue
                try {
                    if (!nic.isUp || nic.isLoopback) continue
                    for (address in nic.interfaceAddresses) {
                        val addr = address.address ?: continue
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            addr.hostAddress?.let { result.add(it) }
                        }
                    }
                } catch (_: Throwable) {
                    // per-interface failure should not break enumeration
                }
            }
        } catch (_: Throwable) {
            // no network stack access
        }
        return result.toList()
    }
}
