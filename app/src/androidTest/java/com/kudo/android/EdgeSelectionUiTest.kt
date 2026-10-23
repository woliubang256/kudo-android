package com.kudo.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kudo.android.data.*
import com.kudo.android.network.*
import com.kudo.android.ui.account.EdgeSelectionCard
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EdgeSelectionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choicesAreIndependentAndDisabledNetworkCannotStartSelection() {
        var configuration by mutableStateOf(DohConfiguration())
        var network by mutableStateOf(DohNetworkState())
        var probes = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            EdgeSelectionCard(configuration, network, { configuration = it }, { probes++ }, false, null, true)
        } }
        compose.onNodeWithTag("edge-enabled").assertIsOff().performClick()
        compose.onNodeWithTag("edge-reselect").assertIsNotEnabled()
        compose.onNodeWithTag("edge-atlas").assertIsOff().performClick()
        compose.runOnIdle {
            assertTrue(configuration.localSelection); assertTrue(configuration.atlas)
            assertFalse(configuration.enabled); assertEquals(BuiltInDohServer, configuration.selected)
            network = DohNetworkState(enabled = true, vpn = true)
        }
        compose.onNodeWithTag("edge-status").assertTextContains("VPN", substring = true)
        compose.onNodeWithTag("edge-reselect").assertIsNotEnabled()
        compose.runOnIdle { network = DohNetworkState(enabled = true) }
        compose.onNodeWithTag("edge-reselect").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, probes) }
    }

    @Test fun selectionProgressAndFallbackCannotLookLikeAVerifiedNode() {
        var network by mutableStateOf(DohNetworkState(enabled = true, edge = EdgeSelectionState("racing")))
        compose.setContent { KudoTheme(DisplaySettings()) {
            EdgeSelectionCard(DohConfiguration(enabled = true, localSelection = true), network, {}, {}, false, null, true)
        } }
        compose.onNodeWithTag("edge-reselect").assertIsNotEnabled()
        compose.onNodeWithTag("edge-node").assertDoesNotExist()
        compose.runOnIdle { network = network.copy(edge = EdgeSelectionState("ready", "104.16.0.1", 86, 1792700000)) }
        compose.onNodeWithTag("edge-node").assertTextEquals("104.16.0.1 · 86 ms")
        compose.onNodeWithTag("edge-reselect").assertIsEnabled()
        compose.runOnIdle { network = network.copy(edge = EdgeSelectionState("fallback")) }
        compose.onNodeWithTag("edge-node").assertDoesNotExist()
        compose.onNodeWithTag("edge-status").assertTextContains("所选 DoH", substring = true)
    }
}
