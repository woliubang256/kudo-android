package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.AboutScreen
import com.kudo.android.ui.account.UpdateSheet
import com.kudo.android.ui.theme.KudoTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class UpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val release = GitHubRelease("1.2.3", "酷DO 1.2.3", "## 新增\n- 支持检查更新\n\n## 修复\n- 头像角标完整显示", "2026-10-11", "$KudoSourceUrl/releases/tag/v1.2.3")

    @Test fun sourceUsesExternalCallbackAndUpdateShowsReleaseNotesAndDownload() {
        var external = ""
        var requests = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            AboutScreen(openLink = { fail("源码不应打开应用内网页") }, openExternal = { external = it },
                checkUpdate = { requests++; release }) {}
        } }
        compose.onNodeWithText("查看源码").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(KudoSourceUrl, external); assertEquals(0, requests) }
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("发现新版本 1.2.3").assertIsDisplayed()
        compose.onNodeWithTag("liquid-dialog").assertIsDisplayed()
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        capture("update-manual-glass.png")
        compose.onNodeWithText("• 支持检查更新").assertIsDisplayed()
        compose.onNodeWithText("• 头像角标完整显示").assertIsDisplayed()
        compose.onNodeWithText("取消").assertDoesNotExist()
        compose.onNodeWithText("前往 GitHub 下载").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(release.url, external); assertEquals(1, requests) }
    }

    @Test fun missingReleaseCanRetryAndCurrentVersionStillDisplaysNotes() {
        var requests = 0
        var external = ""
        compose.setContent { KudoTheme(DisplaySettings()) {
            AboutScreen(openExternal = { external = it }, checkUpdate = {
                requests++
                if (requests == 1) throw UpdateFailure("暂无公开版本")
                release.copy(version = BuildConfig.VERSION_NAME, notes = "")
            }) {}
        } }
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("暂无公开版本").assertIsDisplayed()
        compose.onNodeWithText("当前已是最新版本").assertDoesNotExist()
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("当前已是最新版本").assertIsDisplayed()
        compose.onNodeWithText("此版本暂未提供更新日志。").assertIsDisplayed()
        compose.onNodeWithText("在 GitHub 查看此版本").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, requests); assertEquals(release.url, external) }
    }

    @Test fun closingShortLargeTextWindowCancelsCheck() {
        var cancelled = false
        compose.setContent { KudoTheme(DisplaySettings(fontScale = 1.3f)) { Box(Modifier.fillMaxSize()) {
            Box(Modifier.width(320.dp).height(360.dp)) {
                AboutScreen(checkUpdate = { try { awaitCancellation() } finally { cancelled = true } }) {}
            }
        } } }
        compose.onNodeWithTag("about-content").performScrollToNode(hasText("检查更新"))
        compose.onNodeWithText("检查更新").performClick()
        compose.onNodeWithText("正在检查 GitHub 版本…").assertIsDisplayed()
        compose.onNodeWithTag("modal-scrim").performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("liquid-dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun longNotesScrollWithoutMovingDownloadInFullWindow() {
        compose.setContent { KudoTheme(DisplaySettings(fontScale = 1.3f)) {
            AboutScreen(checkUpdate = { release.copy(notes = (1..60).joinToString("\n") { "- 更新内容 $it" }) }) {}
        } }
        compose.onNodeWithTag("about-content").performScrollToNode(hasText("检查更新"))
        compose.onNodeWithText("检查更新").performClick()
        val download = compose.onNodeWithText("前往 GitHub 下载").assertIsDisplayed()
        val before = download.fetchSemanticsNode().boundsInRoot
        capture("update-long-full-window.png")
        compose.onNodeWithText("• 更新内容 60").performScrollTo().assertIsDisplayed()
        assertEquals(before, download.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
    }

    @Test fun longNotesPanelUsesAtMostEightyPercentOfAvailableHeight() {
        val maximum = showUpdate(height = 640, settings = DisplaySettings(theme = ThemeMode.LIGHT))
        val panel = compose.onNodeWithTag("liquid-dialog").fetchSemanticsNode().boundsInRoot
        assertTrue("长日志面板不得超过可用高度的80%：${panel.height} > $maximum", panel.height <= maximum + 2f)
        capture("update-height-long-light.png")
    }

    @Test fun shortDarkLargeTextWindowKeepsHeaderAndDownloadFixedWhileNotesScroll() {
        var external = ""
        val maximum = showUpdate(height = 360, width = 320, settings = DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f), external = { external = it })
        val download = compose.onNodeWithText("前往 GitHub 下载").assertIsDisplayed()
        val title = compose.onNodeWithText("发现新版本 1.2.3").assertIsDisplayed()
        val beforeDownload = download.fetchSemanticsNode().boundsInRoot
        val beforeTitle = title.fetchSemanticsNode().boundsInRoot
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.onNodeWithText("• 更新内容 60").performScrollTo().assertIsDisplayed()
        assertEquals(beforeDownload, download.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        assertEquals(beforeTitle, title.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        assertTrue(compose.onNodeWithTag("liquid-dialog").fetchSemanticsNode().boundsInRoot.height <= maximum + 2f)
        capture("update-short-dark-large-scrolled.png")
        download.performClick()
        compose.runOnIdle { assertEquals(release.url, external) }
    }

    @Test fun shortNotesDoNotExpandPanelToItsMaximum() {
        val maximum = showUpdate(height = 640, settings = DisplaySettings(theme = ThemeMode.LIGHT), notes = "- 短日志")
        compose.onNodeWithText("• 短日志").assertIsDisplayed()
        compose.onNodeWithText("前往 GitHub 下载").assertIsDisplayed()
        val panel = compose.onNodeWithTag("liquid-dialog").fetchSemanticsNode().boundsInRoot
        assertTrue("短日志应按内容收缩", panel.height < maximum - 20f)
        capture("update-short-notes.png")
    }

    private fun showUpdate(height: Int, width: Int = 360, settings: DisplaySettings = DisplaySettings(),
        notes: String = (1..60).joinToString("\n") { "- 更新内容 $it" }, external: (String) -> Unit = {}): Float {
        var maximum = 0f
        compose.setContent { KudoTheme(settings) { Box(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.width(width.dp).height(height.dp)) {
                val density = LocalDensity.current
                val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
                val bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
                SideEffect { maximum = with(density) { ((maxHeight - top - bottom - 32.dp) * .8f).toPx() } }
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background))
                val visibility = remember { MutableTransitionState(true) }
                UpdateSheet(visibility, { release.copy(notes = notes) }, backdrop, external) { visibility.targetState = false }
            }
        } } }
        compose.waitForIdle()
        compose.onNodeWithText("发现新版本 1.2.3").assertExists()
        return maximum
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
