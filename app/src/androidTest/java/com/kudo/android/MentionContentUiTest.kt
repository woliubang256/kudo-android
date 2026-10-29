package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.EditorPreviewDialog
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.RichContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

class MentionContentUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun mentionHasANeutralBackgroundAndRoutesTheOriginalUsernameInBothThemes() {
        var mode by mutableStateOf(ThemeMode.LIGHT)
        var expected = Color.Transparent
        val links = mutableListOf<String>()
        compose.setContent { KudoTheme(DisplaySettings(theme = mode)) {
            expected = MaterialTheme.colorScheme.onSurface.copy(alpha = .09f).compositeOver(MaterialTheme.colorScheme.surface)
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
                RichContent("<p>你好 <a class='mention' href='/u/neo'>@neo</a> 后文</p>", {}, { links += it })
            }
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { mode = theme }
            val node = compose.onNodeWithText("你好 @neo 后文")
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val bounds = layouts.single().getPathForRange(3, 7).getBounds()
            val pixels = node.captureToImage().toPixelMap()
            var matches = 0
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(pixels.height)) {
                for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(pixels.width)) {
                    val color = pixels[x, y]
                    if (abs(color.red - expected.red) < .015f && abs(color.green - expected.green) < .015f && abs(color.blue - expected.blue) < .015f) matches++
                }
            }
            assertTrue("Mention background must be visible in $theme", matches > bounds.width * bounds.height * .2f)
            capture("mentions-content-$theme.png")
            node.performTouchInput { click(layouts.single().getBoundingBox(4).center) }
        }
        assertEquals(listOf("https://linux.do/u/neo", "https://linux.do/u/neo"), links)
    }

    @Test fun hiddenMentionDoesNotExposeTheUsernameOrOpenTheUserUntilRevealed() {
        val links = mutableListOf<String>()
        compose.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                RichContent("<p><span class='spoiler'><a class='mention' href='/u/hidden'>@hidden</a></span></p>", {}, { links += it })
            }
        } }
        compose.onNodeWithText("@hidden").assertDoesNotExist()
        compose.onNodeWithTag("article-text-root-0").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertTrue(links.isEmpty())
        compose.onNodeWithText("@hidden").assertIsDisplayed()
    }

    @Test fun previewRecognizesMentionsAndKeepsCodeAndEmailsReadable() {
        val raw = "你好 @neo\n\n`@code`\n\n邮箱 a@neo.com"
        compose.setContent { KudoTheme(DisplaySettings()) { EditorPreviewDialog(raw) {} } }
        compose.waitUntil { compose.onAllNodesWithText("你好 @neo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("邮箱 a@neo.com").assertIsDisplayed()
        compose.onNodeWithText("@code").assertIsDisplayed()
        capture("mentions-preview.png")
    }
}
