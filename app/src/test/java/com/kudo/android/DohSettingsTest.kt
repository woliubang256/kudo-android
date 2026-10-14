package com.kudo.android

import com.kudo.android.data.*
import org.junit.Assert.*
import org.junit.Test

class DohSettingsTest {
    @Test fun oldConfigurationsKeepVpnAutoSwitchOnAndExplicitOptOutRoundTrips() {
        val legacy = decodeDohConfiguration("""{"version":1,"enabled":true,"selectedId":"shihuang-doh","servers":[]}""")
        assertTrue(legacy.vpnAutoSwitch)
        assertTrue(legacy.enabled)
        assertEquals(ShihuangDohServer, legacy.selected)
        val server = validateDohServer("分流节点", "https://dns.example/dns-query")
        val optedOut = legacy.copy(vpnAutoSwitch = false).saveServer(server).select(server.id)
        assertEquals(optedOut, decodeDohConfiguration(encodeDohConfiguration(optedOut)))
        assertFalse(optedOut.removeServer(server.id).vpnAutoSwitch)
        assertTrue(decodeDohConfiguration(null).vpnAutoSwitch)
    }

    @Test fun invalidVpnAutoSwitchValuesKeepTheSafeDefaultWithoutDiscardingOtherSettings() {
        for (value in listOf("null", "\"false\"", "0", "{}", "[]")) {
            val restored = decodeDohConfiguration("""{"version":1,"enabled":true,"vpnAutoSwitch":$value}""")
            assertTrue(restored.vpnAutoSwitch)
            assertTrue(restored.enabled)
        }
    }

    @Test fun defaultsOffAndRepairsCorruptOrDanglingSelection() {
        assertFalse(decodeDohConfiguration(null).enabled)
        assertEquals(DohConfiguration(), decodeDohConfiguration("invalid"))
        assertEquals(BuiltInDohServer, decodeDohConfiguration("""{"version":1,"enabled":true,"selectedId":"missing"}""").selected)
    }
    @Test fun builtInListIncludesShihuangWithoutChangingTheDefault() {
        val value = DohConfiguration()
        assertEquals(listOf(BuiltInDohServer, ShihuangDohServer), value.servers)
        assertEquals("始皇DoH", ShihuangDohServer.name)
        assertEquals("https://kudo.ddd.oaifree.com/query-dns", ShihuangDohServer.url)
        assertTrue(value.servers.all { it.builtIn })
        assertEquals(BuiltInDohServer, value.selected)
        assertEquals(BuiltInDohServer, decodeDohConfiguration("""{"version":1,"enabled":true,"selectedId":"linuxdo-community","servers":[]}""").selected)
    }
    @Test fun selectingShihuangRoundTripsAlongsideCustomServers() {
        val custom = validateDohServer("自定义", "https://dns.example/dns-query")
        val value = DohConfiguration(enabled = true).saveServer(custom).select(ShihuangDohServer.id)
        val restored = decodeDohConfiguration(encodeDohConfiguration(value))
        assertEquals(value, restored)
        assertEquals(ShihuangDohServer, restored.selected)
        assertEquals(listOf(custom), restored.customServers)
        assertEquals(ShihuangDohServer, restored.removeServer(custom.id).selected)
    }
    @Test fun customServersRoundTripEditSelectAndDelete() {
        val server = validateDohServer("测试", "https://dns.example/dns-query?key=private", "1.1.1.1,2606:4700:4700::1111")
        val value = DohConfiguration(enabled = true).saveServer(server).select(server.id)
        assertEquals(value, decodeDohConfiguration(encodeDohConfiguration(value)))
        val edited = value.saveServer(server.copy(name = "修改", url = "https://dns.example/new"))
        assertEquals("修改", edited.selected.name)
        assertEquals(1, edited.customServers.size)
        assertEquals(BuiltInDohServer, edited.removeServer(server.id).selected)
        assertTrue(edited.removeServer(server.id).enabled)
    }
    @Test fun rejectsInsecureCredentialsFragmentsAndNonIpBootstrap() {
        for (url in listOf("http://dns.example/", "https://user:password@dns.example/", "https://dns.example/#fragment", "garbage")) {
            assertTrue(url, runCatching { validateDohServer("测试", url) }.isFailure)
        }
        for (ip in listOf("dns.example", "256.1.1.1", "01.1.1.1", "fe80::1%wlan0", "[::1]")) {
            assertTrue(ip, runCatching { validateDohServer("测试", "https://dns.example/", ip) }.isFailure)
        }
    }
    @Test fun allBuiltInServersRejectEditingAndDeletion() {
        for (server in BuiltInDohServers) {
            assertTrue(server.id, runCatching { DohConfiguration().removeServer(server.id) }.isFailure)
            assertTrue(server.id, runCatching { DohConfiguration().saveServer(server.copy(name = "覆盖", url = "https://override.example/")) }.isFailure)
        }
    }
    @Test fun invalidSavedServerCannotOverrideBuiltInOrRemainSelected() {
        val raw = """{"version":1,"enabled":false,"selectedId":"bad","servers":[{"id":"bad","name":"bad","url":"http://example/"},{"id":"linuxdo-community","name":"override","url":"https://evil.example/"}]}"""
        assertEquals(DohConfiguration(), decodeDohConfiguration(raw))
    }
    @Test fun savedCustomServerCannotOverrideAnyBuiltInId() {
        for (server in BuiltInDohServers) {
            val raw = """{"version":1,"enabled":true,"selectedId":"${server.id}","servers":[{"id":"${server.id}","name":"覆盖","url":"https://override.example/"}]}"""
            val value = decodeDohConfiguration(raw)
            assertEquals(server, value.selected)
            assertTrue(value.customServers.isEmpty())
        }
    }
}
