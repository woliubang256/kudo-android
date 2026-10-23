package com.kudo.android

import com.kudo.android.data.*
import com.kudo.android.network.*
import org.junit.Assert.*
import org.junit.Test

class EdgeSelectionSettingsTest {
    @Test fun oldSettingsKeepTheirServerAndDoNotStartSelectionOrAtlas() {
        val old = decodeDohConfiguration("""{"version":1,"enabled":true,"selectedId":"shihuang-doh","vpnAutoSwitch":false}""")
        assertTrue(old.enabled); assertFalse(old.localSelection); assertFalse(old.atlas)
        assertFalse(old.vpnAutoSwitch); assertEquals(ShihuangDohServer, old.selected)
        val next = old.copy(localSelection = true, atlas = true)
        assertEquals(next, decodeDohConfiguration(encodeDohConfiguration(next)))
        assertEquals(next, next.select(ShihuangDohServer.id))
    }
    @Test fun untypedOptionsAndMalformedStatusDoNotEnableTrafficOrExposeRawText() {
        val bad = decodeDohConfiguration("""{"version":1,"localSelection":"true","atlas":1}""")
        assertFalse(bad.localSelection); assertFalse(bad.atlas)
        assertEquals(EdgeSelectionState(), decodeEdgeSelectionState("broken private response"))
        val status = decodeEdgeSelectionState("""{"phase":"<script>error</script>","winner":{"ip":"secret cookie", "latency_ms":-1}}""")
        assertEquals("idle", status.phase); assertNull(status.ip); assertNull(status.latencyMs)
        assertFalse(status.summary.contains("secret"))
    }
    @Test fun displaysVerifiedNodeAndDistinguishesFallbackFromSuccess() {
        val state = decodeEdgeSelectionState("""{"phase":"ready","winner":{"ip":"2606:4700::1","latency_ms":82,"checked_at":1792700000}}""")
        assertEquals("2606:4700::1", state.ip); assertEquals(82L, state.latencyMs); assertFalse(state.busy)
        assertTrue(decodeEdgeSelectionState("""{"phase":"racing"}""").busy)
        assertTrue(decodeEdgeSelectionState("""{"phase":"fallback"}""").summary.contains("所选 DoH"))
        assertFalse(DohNetworkState(enabled = true, vpn = true, edge = state).effective)
    }
}
