package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.AboutScreen
import com.kudo.android.ui.account.ProfileScreen
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class GuestProfileUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun guestActionsHaveSeparateDestinationsAndNoAccountShortcuts() {
        var logins = 0
        var settings = 0
        var about = 0
        var accountActions = 0
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
                ProfileScreen(null, { logins++ }, { settings++ }, { accountActions++ }, { accountActions++ }, about = { about++ })
            }
        }
        compose.onNodeWithText("我的收藏").assertDoesNotExist()
        compose.onNodeWithText("夜间模式").assertDoesNotExist()
        compose.onNodeWithText("你好，DO 友").assertDoesNotExist()
        compose.onNodeWithText("点击登录").assertIsDisplayed().performClick()
        compose.onNodeWithText("按目录管理收藏图片").assertDoesNotExist()
        compose.onNodeWithText("图库管理").assertHeightIsEqualTo(56.dp).assertTouchHeightIsEqualTo(56.dp)
        compose.onNodeWithContentDescription("设置").assertDoesNotExist()
        compose.onNodeWithText(BuildConfig.VERSION_NAME).assertIsDisplayed()
        compose.onNodeWithText("真诚 · 友善 · 团结 · 专业").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("关于酷DO").performClick()
        compose.runOnIdle {
            assertEquals(1, logins); assertEquals(1, settings); assertEquals(1, about); assertEquals(0, accountActions)
        }
        capture("guest-profile-light.png", "guest-profile")
    }

    @Test fun signingInAndOutSwitchesBranchesWithoutKeepingGuestContent() {
        var member by mutableStateOf<Member?>(null)
        var profiles = 0
        compose.setContent {
            KudoTheme(DisplaySettings()) { ProfileScreen(member, {}, {}, {}, { profiles++ }) }
        }
        compose.onNodeWithText("点击登录").assertIsDisplayed()
        compose.runOnIdle { member = Member(42, "fixture", "账号分支测试", "", trustLevel = 3) }
        compose.onNodeWithTag("guest-profile").assertDoesNotExist()
        compose.onNodeWithText("账号分支测试").assertIsDisplayed()
        compose.onNodeWithText("我的收藏").assertIsDisplayed()
        compose.onNodeWithText("查看个人主页  →").assertDoesNotExist()
        compose.onNodeWithTag("member-avatar").performClick()
        compose.runOnIdle { assertEquals(1, profiles); member = null }
        compose.onNodeWithTag("guest-profile").assertIsDisplayed()
        compose.onNodeWithText("我的收藏").assertDoesNotExist()
        compose.onNodeWithText("账号分支测试").assertDoesNotExist()
    }

    @Test fun narrowDarkLargeTextCanScrollToEveryActionWithoutTruncation() {
        var about = 0
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.width(280.dp).height(420.dp).testTag("guest-narrow")) {
                        ProfileScreen(null, {}, {}, {}, {}, about = { about++ })
                    }
                }
            }
        }
        compose.onNodeWithText("点击登录").assertIsDisplayed()
        compose.onNodeWithText("关于酷DO").performScrollTo().assertIsDisplayed().performClick()
        capture("guest-profile-dark-narrow-large.png", "guest-narrow")
        compose.onNodeWithText("按目录管理收藏图片").assertDoesNotExist()
        for (label in listOf("设置", "图库管理", "关于酷DO", BuildConfig.VERSION_NAME, "一个用心的 Linux.do 原生客户端")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // Semantics can retain a paragraph's available width while the Text itself wraps to its intrinsic width.
            // Check the actual glyph lines rather than treating that available width as clipped text.
            assertFalse("文字高度不能被截断：$label", layout.didOverflowHeight)
            for (line in 0 until layout.lineCount) {
                assertFalse("文字不能被省略：$label", layout.isLineEllipsized(line))
                assertTrue("文字不能超出边界：$label", layout.getLineRight(line) <= layout.size.width + 1f)
            }
        }
        compose.runOnIdle { assertEquals(1, about) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithText("真诚 · 友善 · 团结 · 专业").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("点击登录").assertIsDisplayed()
    }

    @Test fun aboutShowsVersionInUpdateRowAndBackReturnsToGuest() {
        var showingAbout by mutableStateOf(true)
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
                if (showingAbout) AboutScreen { showingAbout = false }
                else ProfileScreen(null, {}, {}, {}, {})
            }
        }
        compose.onNode(hasText("检查更新") and hasText(BuildConfig.VERSION_NAME)).assertIsDisplayed()
        compose.onAllNodesWithText(BuildConfig.VERSION_NAME, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("酷DO是 LINUX DO 的第三方安卓原生客户端").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("点击登录").assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.VERSION_NAME).assertIsDisplayed()
    }

    private fun capture(name: String, tag: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
