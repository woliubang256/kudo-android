package com.kudo.android.network

import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Prefer other current DNS candidates after TCP failures; never remove or cache addresses. */
internal class DohAddressOrder(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Address(val host: String, val ip: InetAddress)
    private val failures = linkedMapOf<Address, Long>()

    @Synchronized
    fun order(host: String, candidates: List<InetAddress>): List<InetAddress> {
        expire()
        return candidates.sortedBy { failures.containsKey(Address(host.lowercase(), it)) }
    }

    @Synchronized
    fun failed(host: String, ip: InetAddress, error: IOException) {
        if (error !is SocketTimeoutException && error.message !in setOf("DoH: connection_failed",
                "DoH: connect_refused", "DoH: connect_unreachable", "DoH: connect_reset", "DoH: connect_timeout", "DoH: connect_failed")) return
        expire()
        val key = Address(host.lowercase(), ip)
        failures.remove(key)
        if (failures.size >= 256) failures.remove(failures.keys.first())
        failures[key] = nowMillis() + 10_000
    }

    private fun expire() {
        val now = nowMillis()
        failures.entries.removeAll { it.value <= now }
    }
}
