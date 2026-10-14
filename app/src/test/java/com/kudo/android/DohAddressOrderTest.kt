package com.kudo.android

import com.kudo.android.network.DohAddressOrder
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException

class DohAddressOrderTest {
    private fun ip(last: Int) = InetAddress.getByAddress(byteArrayOf(127, 0, 0, last.toByte()))
    private val first = ip(2)
    private val second = ip(1)
    private val candidates = listOf(first, second)

    @Test fun tcpFailuresPreferAnotherCandidateWithoutRemovingTheFailedAddress() {
        val order = DohAddressOrder()
        order.failed("image.test", first, IOException("DoH: connection_failed"))
        assertEquals(listOf(second, first), order.order("image.test", candidates))
        order.failed("image.test", second, SocketTimeoutException("DoH: socket_timeout"))
        assertEquals(candidates, order.order("image.test", candidates))
        assertEquals(listOf(first), order.order("image.test", listOf(first)))
    }

    @Test fun failuresExpireAndNeverCrossHostOrTransportBoundaries() {
        var now = 0L
        val order = DohAddressOrder { now }
        order.failed("IMAGE.test", first, IOException("DoH: connection_failed"))
        assertEquals(listOf(second, first), order.order("image.test", candidates))
        assertEquals(candidates, order.order("other.test", candidates))
        assertEquals(candidates, DohAddressOrder().order("image.test", candidates))
        now = 9_999
        assertEquals(listOf(second, first), order.order("image.test", candidates))
        now = 10_000
        assertEquals(candidates, order.order("image.test", candidates))
    }

    @Test fun updatedDnsCandidatesAreNotReplacedWithRememberedIps() {
        val order = DohAddressOrder()
        order.failed("image.test", first, IOException("DoH: connection_failed"))
        assertEquals(listOf(ip(3), second), order.order("image.test", listOf(ip(3), second)))
        assertTrue(order.order("image.test", emptyList()).isEmpty())
    }

    @Test fun configurationTlsAndUnknownErrorsDoNotChangeAddressOrder() {
        for (code in listOf("profile_closed", "socket_closed", "ech_rejected", "tls_record_invalid", "unknown", "read_reset", "write_reset")) {
            val order = DohAddressOrder()
            order.failed("image.test", first, IOException("DoH: $code"))
            assertEquals(candidates, order.order("image.test", candidates))
        }
    }

    @Test fun newNativeConnectCodesStillPreferTheOtherAddress() {
        for (code in listOf("connect_refused", "connect_unreachable", "connect_reset", "connect_timeout", "connect_failed")) {
            val order = DohAddressOrder()
            order.failed("image.test", first, IOException("DoH: $code"))
            assertEquals(listOf(second, first), order.order("image.test", candidates))
        }
    }

    @Test fun rememberingFailuresHasABoundedCapacity() {
        val order = DohAddressOrder()
        repeat(257) { order.failed("image-$it.test", first, IOException("DoH: connection_failed")) }
        assertEquals(candidates, order.order("image-0.test", candidates))
        assertEquals(listOf(second, first), order.order("image-1.test", candidates))
        assertEquals(listOf(second, first), order.order("image-256.test", candidates))
    }
}
