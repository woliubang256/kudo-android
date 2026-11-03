package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.EditorToolBackdropHost
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

class PostDeviceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val member = Member(8, "member", "回复作者", "", avatarDetailsLoaded = true)
    private val post = Post(8, 8, member, "<p>正文</p>", "", 0, false, null, 2, true,
        device = PostDevice("iPhone 14 Pro Max", "phone"))

    @Test fun clickShowsModelAndOutsideTapBackAndModelChangeDismissIt() {
        var current by mutableStateOf(post)
        compose.setContent { KudoTheme(DisplaySettings()) { EditorToolBackdropHost {
            Column(Modifier.fillMaxSize().padding(top = 100.dp)) {
                PostDeviceIndicator(current, true)
                Text("其他位置", Modifier.clickable {}.padding(30.dp).testTag("outside-device"))
            }
        } } }
        compose.onNodeWithText("回复自 iPhone 14 Pro Max").assertDoesNotExist()
        compose.onNodeWithTag("post-device-8").performClick()
        compose.onNodeWithText("回复自 iPhone 14 Pro Max").assertIsDisplayed()
        compose.onNodeWithTag("outside-device").performTouchInput { click() }
        compose.onNodeWithTag("post-device-tooltip-8").assertDoesNotExist()
        compose.onNodeWithTag("post-device-8").performClick()
        compose.onNodeWithText("回复自 iPhone 14 Pro Max").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("post-device-tooltip-8").assertDoesNotExist()
        compose.onNodeWithTag("post-device-8").performClick()
        compose.runOnIdle { current = post.copy(device = PostDevice("iPad Pro", "tablet")) }
        compose.onNodeWithTag("post-device-tooltip-8").assertDoesNotExist()
        compose.onNodeWithTag("post-device-8").performClick()
        compose.onNodeWithText("回复自 iPad Pro").assertIsDisplayed()
        compose.runOnIdle { current = post.copy(device = null) }
        compose.onNodeWithTag("post-device-8").assertDoesNotExist()
        compose.onNodeWithTag("post-device-tooltip-8").assertDoesNotExist()
    }

    @Test fun postAndThreadHeadersKeepDeviceActionsOnNarrowLargeText() {
        val original = post.copy(id = 1, number = 1, replyTo = null)
        val thread = post.copy(id = 18, number = 18)
        val detail = TopicDetail(5, "话题", 1, listOf(original, post, thread), listOf(1, 8, 18), false)
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) { EditorToolBackdropHost {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Column(Modifier.width(320.dp)) {
                    TopicTopBar(TopicState(detail, false), true, {}, {}, {}, {})
                    TopicPost(post.copy(replyToUser = member.copy(name = "被回复作者")), detail, TopicState(detail, false), true,
                        {}, {}, {}, {}, {}, {}, {})
                    ThreadPreviewPost(thread, true, {}, {}, {}, "甲 回复 乙", TopicState(detail, false), {}, {}, {})
                }
            }
        } } }
        compose.onNodeWithTag("post-device-1").performClick()
        compose.onNodeWithText("发布自 iPhone 14 Pro Max").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("post-device-8").performClick()
        compose.onNodeWithText("回复自 iPhone 14 Pro Max").assertIsDisplayed()
        screenshot("post-device-dark-large.png").recycle()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("post-device-18").performClick()
        compose.onNodeWithText("回复自 iPhone 14 Pro Max").assertIsDisplayed()
    }

    @Test fun deviceTooltipSamplesChangingBackgroundInBothThemesAndAllMaterials() {
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        var source by mutableStateOf(Color(0xFFB6314D))
        compose.setContent { KudoTheme(settings) { EditorToolBackdropHost {
            Box(Modifier.fillMaxSize().background(source), contentAlignment = Alignment.Center) { PostDeviceIndicator(post, true) }
        } } }
        compose.onNodeWithTag("post-device-8").performClick()
        fun sample(): Color {
            val panel = compose.onNodeWithTag("post-device-tooltip-8").fetchSemanticsNode()
            val bitmap = screenshot()
            return try { Color(bitmap.getPixel((panel.positionOnScreen.x + 8).toInt(),
                (panel.positionOnScreen.y + panel.size.height / 2f).toInt())) } finally { bitmap.recycle() }
        }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (mode in GlassMode.entries) {
            compose.runOnIdle { settings = settings.copy(theme = theme, glass = mode); source = Color(0xFFB6314D) }
            val red = sample()
            compose.runOnIdle { source = Color(0xFF2564C3) }
            val blue = sample()
            val difference = abs(red.red - blue.red) + abs(red.blue - blue.blue)
            println("Device tooltip material $theme/$mode difference=$difference")
            assertTrue("$theme/$mode must sample its sibling background", difference > if (mode == GlassMode.TRANSLUCENT) .02f else .1f)
            compose.onNodeWithText("回复自 iPhone 14 Pro Max").assertIsDisplayed()
            screenshot("post-device-${theme.name.lowercase()}-${mode.name.lowercase()}.png").recycle()
        }
    }

    private fun screenshot(name: String? = null): Bitmap {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (name != null) File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }
}
