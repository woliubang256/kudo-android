package com.kudo.android

import com.kudo.android.network.DohNetworkState
import org.junit.Assert.*
import org.junit.Test

class DohNetworkPolicyTest {
    @Test fun vpnAutoSwitchControlsBothVpnAndProxyWhileTheMasterSwitchAlwaysWins() {
        for (enabled in listOf(false, true)) for (autoSwitch in listOf(false, true))
            for (vpn in listOf(false, true)) for (proxy in listOf(false, true)) {
                val state = DohNetworkState(enabled = enabled, vpn = vpn, proxy = proxy, vpnAutoSwitch = autoSwitch)
                assertEquals(autoSwitch && (vpn || proxy), state.pausedByVpnOrProxy)
                assertEquals(enabled && (!autoSwitch || (!vpn && !proxy)), state.effective)
                if (!enabled) assertEquals("已关闭", state.summary)
            }
    }

    @Test fun optingOutShowsTheActualConnectionStateInsteadOfClaimingVpnPausedDoh() {
        val state = DohNetworkState(enabled = true, vpn = true, proxy = true, vpnAutoSwitch = false)
        assertEquals("已开启，等待连接", state.summary)
        assertEquals("DoH + ECH 已连接", state.copy(echAccepted = true).summary)
        assertEquals("连接失败", state.copy(error = "连接失败").summary)
        assertEquals("VPN 已开启，DoH 暂停", state.copy(vpnAutoSwitch = true).summary)
        assertEquals("系统代理已开启，DoH 暂停", state.copy(vpn = false, vpnAutoSwitch = true).summary)
    }

    @Test fun defaultOffAndVpnOnlyPausesTheSavedIntent() {
        assertFalse(DohNetworkState().effective)
        val enabled = DohNetworkState(enabled = true)
        assertTrue(enabled.effective)
        val paused = enabled.copy(vpn = true)
        assertTrue(paused.enabled)
        assertFalse(paused.effective)
        assertTrue(paused.copy(vpn = false).effective)
        assertFalse(paused.copy(enabled = false, vpn = false).effective)
    }
    @Test fun systemProxyAlsoPausesAndErrorsNeverClaimEchAccepted() {
        assertFalse(DohNetworkState(enabled = true, proxy = true).effective)
        assertTrue(DohNetworkState(enabled = true, error = "失败").effective)
        assertEquals("失败", DohNetworkState(enabled = true, error = "失败").summary)
        assertEquals("DoH + ECH 已连接", DohNetworkState(enabled = true, echAccepted = true).summary)
    }
}
