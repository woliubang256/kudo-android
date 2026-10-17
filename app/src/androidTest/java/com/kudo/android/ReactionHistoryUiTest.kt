package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReactionHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private val post = Post(42, 1, Member(1, "author", "作者", ""), "", "", 0, false, null, null, true)
    private val history = ReactionHistory(listOf(
        ReactionHistoryGroup("heart", 80, listOf(ReactionHistoryUser(Member(0, "alice", "甲", ""), "heart"))),
        ReactionHistoryGroup("laughing", 20, listOf(ReactionHistoryUser(Member(0, "bob", "乙", ""), "laughing"))),
    ))

    @Test fun sheetFiltersRealUsersAndNavigatesWithoutWritingAReaction() {
        val visited = mutableListOf<String>()
        var reads = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ -> reads++; history }, { visited += it }) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("reaction-history-filter-all").assert(hasText("全部 100"))
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-user-bob").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-filter-laughing").performClick()
        compose.onNodeWithTag("reaction-history-user-alice").assertDoesNotExist()
        compose.onNodeWithTag("reaction-history-user-bob").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-partial").assertIsDisplayed()
        capture("reaction-history-light.png")
        compose.onNodeWithTag("reaction-history-user-bob").performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("bob"), visited); assertEquals(1, reads) }
    }

    @Test fun sheetShowsLoadingRetryAndCanBeDraggedClosed() {
        val release = CompletableDeferred<Unit>()
        var requests = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ ->
                requests++
                if (requests == 1) { release.await(); error("测试网络失败") }
                history
            }, {}) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("reaction-history-retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reaction-history-retry").performClick()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.onNodeWithTag("sheet-drag-handle").performTouchInput { swipeDown(startY = centerY, endY = centerY + 450f) }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun changingAccountOrScreenClosesTheOldHistory() {
        var account by mutableStateOf(1)
        var screen by mutableStateOf("topic1")
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReactionHistoryHost(account, screen, GlassMode.TRANSLUCENT, { _, _ -> history }, {}) {
                val open = LocalReactionHistory.current
                TextButton(onClick = { open(post) }) { Text("打开历史") }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.runOnIdle { account = 2 }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        compose.runOnIdle { screen = "topic2" }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
    }

    @Test fun filtersWrapOnNarrowLargeFontScreens() {
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp).fillMaxHeight()) {
                    TopicReactionHistoryHost(1, "topic", GlassMode.TRANSLUCENT, { _, _ -> history }, {}) {
                        val open = LocalReactionHistory.current
                        TextButton(onClick = { open(post) }) { Text("打开历史") }
                    }
                }
            }
        } }
        compose.onNodeWithText("打开历史").performClick()
        compose.onNodeWithTag("reaction-history-filter-all").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-filter-laughing").assertIsDisplayed()
        compose.onNodeWithTag("reaction-history-user-alice").assertIsDisplayed()
        capture("reaction-history-narrow.png")
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
