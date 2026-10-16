package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.AccountTabs
import com.kudo.android.ui.components.*
import com.kudo.android.ui.feed.FeedTabBar
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PageMaterialUiTest {
    @get:Rule val compose = createComposeRule()
    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    @Test fun twoAndThreeTabsDivideAvailableSpaceAndKeepTheirLabelsCentered() {
        var account by mutableStateOf(true)
        var large by mutableStateOf(false)
        val labels = listOf("帖子", "类别与标签", "用户")
        compose.setContent { KudoTheme(DisplaySettings(fontScale = if (large) 1.3f else 1f)) {
            Column(Modifier.width(360.dp).testTag("tabs-area")) {
                if (account) AccountTabs(listOf("话题", "回复"), 0) {}
                else FeedTabBar(labels.map { it to it }, labels[0]) {}
            }
        } }
        for (kind in listOf(true, false)) {
            compose.runOnIdle { account = kind }
            val expected = if (kind) listOf("话题", "回复") else labels
            val area = compose.onNodeWithTag("tabs-area").fetchSemanticsNode().boundsInRoot
            expected.forEachIndexed { index, label ->
                val bounds = tab(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(area.width / expected.size, bounds.width, 2f)
                assertEquals(area.left + (index + .5f) * area.width / expected.size, bounds.center.x, 2f)
                val text = compose.onNode(hasText(label) and !hasClickAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertEquals(bounds.center.x, text.center.x, 2f)
            }
            capture(if (kind) "page-tabs-account.png" else "page-tabs-search.png")
        }
        compose.runOnIdle { account = true; large = true }
        val first = tab("话题").fetchSemanticsNode().boundsInRoot
        val second = tab("回复").fetchSemanticsNode().boundsInRoot
        assertEquals(first.width, second.width, 2f)
    }

    @Test fun crowdedTabsScrollToTheSelectedItemInsteadOfClippingLabels() {
        var selected by mutableStateOf("0")
        val tabs = (0..7).map { "$it" to "导航标签$it" }
        compose.setContent { KudoTheme(DisplaySettings(fontScale = 1.3f)) {
            Box(Modifier.width(280.dp)) { FeedTabBar(tabs, selected) { selected = it } }
        } }
        compose.runOnIdle { selected = "7" }
        tab("导航标签7").assertIsDisplayed().assertIsSelected()
        tab("导航标签0").assertIsNotDisplayed()
        compose.runOnIdle { selected = "0" }
        tab("导航标签0").assertIsDisplayed().assertIsSelected()
    }

    @Test fun selectedTabRemainsVisibleWhenTheWindowBecomesNarrow() {
        var width by mutableStateOf(500.dp)
        val labels = listOf("帖子", "类别与标签", "用户")
        compose.setContent { KudoTheme(DisplaySettings()) {
            Box(Modifier.width(width)) { FeedTabBar(labels.map { it to it }, "用户") {} }
        } }
        tab("用户").assertIsDisplayed()
        compose.runOnIdle { width = 160.dp }
        tab("用户").assertIsDisplayed().assertIsSelected()
    }

    @Test fun independentToolbarActionsKeepSpacingTouchTargetsAndDisabledState() {
        var refreshes = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            Box(Modifier.width(280.dp)) {
                MaterialPageScaffold(header = {
                    PageTopBar("很长的页面标题", {}) {
                        CommunityIconButton(Icons.Rounded.Refresh, "刷新夹具", { refreshes++ })
                        CommunityIconButton(Icons.Rounded.Delete, "禁用夹具", { refreshes++ }, enabled = false)
                    }
                }) { padding ->
                    Column(Modifier.padding(padding)) {
                        CommunityIconButton(Icons.Rounded.Add, "正文普通按钮", {})
                        Text("首屏内容", Modifier.testTag("first-content"))
                    }
                }
            }
        } }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val back = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
        val refresh = compose.onNodeWithContentDescription("刷新夹具").fetchSemanticsNode().boundsInRoot
        val disabled = compose.onNodeWithContentDescription("禁用夹具").assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
        assertEquals(48f * density, back.width, 2f)
        assertEquals(back.width, refresh.width, 2f)
        assertTrue(disabled.left - refresh.right >= 7f * density)
        assertTrue(refresh.left >= back.right)
        compose.onNodeWithContentDescription("刷新夹具").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        val header = compose.onNodeWithTag("material-page-header").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithTag("first-content").fetchSemanticsNode().boundsInRoot.top >= header.bottom)
        capture("page-material-narrow-dark.png")
    }

    @Test fun scrollingContentIsSampledByToolbarInLightDarkAndEveryMaterial() {
        var settings by mutableStateOf(DisplaySettings())
        compose.setContent { KudoTheme(settings) {
            MaterialPageScaffold(header = { PageTopBar("采样", {}) { CommunityIconButton(Icons.Rounded.Refresh, "刷新", {}) } }) { padding ->
                LazyColumn(Modifier.fillMaxSize().testTag("sample-list"), contentPadding = padding) {
                    items(30) { index -> Box(Modifier.fillMaxWidth().height(160.dp)
                        .background(if (index < 5) Color.Red else Color.Blue)) }
                }
            }
        } }
        for (dark in listOf(false, true)) for (mode in GlassMode.entries) {
            compose.runOnIdle { settings = settings.copy(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, glass = mode) }
            compose.onNodeWithTag("sample-list").performScrollToIndex(2)
            val red = headerColor()
            compose.onNodeWithTag("sample-list").performScrollToIndex(8)
            val blue = headerColor()
            if (mode != GlassMode.TRANSLUCENT) {
                assertTrue("$dark/$mode 顶部需采样红色列表", red.red > red.blue + .2f)
                assertTrue("$dark/$mode 顶部需采样蓝色列表", blue.blue > blue.red + .2f)
            } else {
                assertTrue("半透明保留较高主题遮罩", kotlin.math.abs(blue.blue - blue.red) < .15f)
            }
            capture("page-material-${if (dark) "dark" else "light"}-${mode.name.lowercase()}.png")
        }
    }

    private fun headerColor(): Color {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("material-page-header").fetchSemanticsNode().boundsInRoot
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        return pixels[(bounds.center.x + 25).toInt(), (bounds.bottom - 5).toInt()]
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-tests/$name")
        output.parentFile!!.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
