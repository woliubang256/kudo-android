package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReplyPreviewImagesUiTest {
    @get:Rule val compose = createComposeRule()
    private val author = Member(2, "reply_author", "回复者", "", avatarDetailsLoaded = true)
    private val root = Post(20, 2, author, "<p>原评论</p>", "", 0, false, null, null, true)
    private fun image(name: String) = """<div class="lightbox-wrapper"><a class="lightbox" href="/uploads/original/$name.png"><img src="/uploads/optimized/$name.png" alt="image" width="789" height="399"><div class="meta"><span class="filename">image</span><span class="informations">789×399 28.5 KB</span></div></a></div>"""

    @Test fun imageOnlyPreviewOpensOriginalWithoutJumpingToTheFloor() {
        val gallery = ImageGalleryState()
        var jumped: Int? = null
        val post = root.copy(id = 30, number = 3, replyTo = 2, cooked = image("first"))
        assertEquals(1, parseArticle(post.cooked).articleImages().size)
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalImageGallery provides gallery) {
                ReplyPreview(PostThread(root, listOf(post)), null, false, true) { jumped = it }
            }
        } }
        compose.onNodeWithText("回复者：", substring = true).assertIsDisplayed()
        compose.onNodeWithText("789×399", substring = true).assertDoesNotExist()
        compose.onNodeWithText("28.5 KB", substring = true).assertDoesNotExist()
        assertImageFollowsText("回复者：")
        capture("image-only")
        compose.onNodeWithTag("reply-preview-images-30", useUnmergedTree = true)
            .assertExists().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertNull(jumped)
            assertEquals("https://linux.do/uploads/original/first.png", gallery.session!!.current.originalUrl)
            assertEquals(1, gallery.session!!.images.size)
        }
    }

    @Test fun imageActionFollowsShortTextOnTheSameLineAndTextStillJumps() {
        val gallery = ImageGalleryState()
        var jumped: Int? = null
        val post = root.copy(id = 30, number = 3, replyTo = 2,
            cooked = "<p>回来看看</p>" + image("first") + image("second"))
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalImageGallery provides gallery) {
                ReplyPreview(PostThread(root, listOf(post)), null, false, true) { jumped = it }
            }
        } }
        assertImageFollowsText("回来看看")
        capture("short-text")
        val text = compose.onNodeWithText("回来看看", substring = true)
        val layout = layout(text)
        val character = layout.getBoundingBox(layout.layoutInput.text.text.indexOf("回来看看"))
        text.performTouchInput { click(character.center) }
        compose.runOnIdle {
            assertEquals(3, jumped)
            assertNull(gallery.session)
        }
    }

    @Test fun narrowLargeTextKeepsTheImageActionInsideTheTwoLinePreview() {
        val gallery = ImageGalleryState()
        val post = root.copy(id = 30, number = 3, replyTo = 2,
            cooked = "<p>较长的回复文字" + "继续阅读正文内容".repeat(12) + "</p>" + image("first"))
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f),
                LocalImageGallery provides gallery) {
                Box(Modifier.width(280.dp)) {
                    ReplyPreview(PostThread(root, listOf(post)), null, false, true) {}
                }
            }
        } }
        val text = compose.onNodeWithText("较长的回复文字", substring = true)
        val layout = layout(text)
        val textBounds = text.fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag("reply-preview-images-30", useUnmergedTree = true)
        action.assertIsDisplayed()
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        capture("narrow-large-text")
        assertEquals(2, layout.lineCount)
        assertTrue("图片入口应在第二行的正文区域内", actionBounds.top >= textBounds.top + layout.getLineTop(1) - 2f)
        assertTrue("图片入口不得另起第三行: $actionBounds / $textBounds",
            actionBounds.bottom <= textBounds.bottom + 2f)
        compose.onNodeWithText("查看图片", useUnmergedTree = true).assertIsDisplayed()
        action.performTouchInput { click() }
        compose.runOnIdle { assertNotNull(gallery.session) }
    }

    @Test fun imageActionFollowsTheLastExplicitLineAndLargeCountsAreNotClipped() {
        var jumped: Int? = null
        val post = root.copy(id = 30, number = 3, replyTo = 2,
            cooked = "<p>第一行</p><p>末尾<br></p>" + (1..12).joinToString("") { image("image$it") })
        compose.setContent { KudoTheme(DisplaySettings()) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                ReplyPreview(PostThread(root, listOf(post)), null, false, true) { jumped = it }
            }
        } }
        assertImageFollowsText("末尾")
        val label = compose.onNodeWithText("查看图片(12)", useUnmergedTree = true)
        label.assertIsDisplayed()
        capture("multiline-large-count")
        val labelLayout = layout(label)
        val lastCharacter = labelLayout.getBoundingBox(labelLayout.layoutInput.text.lastIndex)
        assertTrue("多图数量的末尾字符不得被裁切", lastCharacter.right <= labelLayout.size.width + 1f)
        assertFalse("多图数量不得被省略", labelLayout.isLineEllipsized(0))
        compose.onNodeWithTag("reply-preview-images-30", useUnmergedTree = true).performTouchInput { click() }
        compose.runOnIdle { assertEquals(3, jumped) }
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun assertImageFollowsText(value: String) {
        val text = compose.onNodeWithText(value, substring = true)
        val layout = layout(text)
        val index = layout.layoutInput.text.text.indexOf(value) + value.lastIndex
        val lastCharacter = layout.getBoundingBox(index)
        val textBounds = text.fetchSemanticsNode().boundsInRoot
        val actionBounds = compose.onNodeWithTag("reply-preview-images-30", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("图片入口应和文字末尾在同一行",
            kotlin.math.abs(actionBounds.center.y - (textBounds.top + lastCharacter.center.y)) < lastCharacter.height / 2f)
        val gap = actionBounds.left - (textBounds.left + lastCharacter.right)
        assertTrue("图片入口应紧跟文字末尾", gap >= -2f && gap < lastCharacter.height)
    }

    private fun capture(name: String) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(directory, "reply-preview-inline-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun textAndEmojiRemainAndMultiplePicturesHaveTheirOwnGallery() {
        val gallery = ImageGalleryState()
        val post = root.copy(id = 30, number = 3, replyTo = 2,
            cooked = "<p>回来看看<img class=\"emoji\" src=\"/images/emoji/twitter/smile.png\" alt=\":smile:\"></p>" + image("first") + image("second"))
        assertEquals(2, parseArticle(post.cooked).articleImages().size)
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            CompositionLocalProvider(LocalImageGallery provides gallery) {
                ReplyPreview(PostThread(root, listOf(post)), null, false, true) {}
            }
        } }
        compose.onNodeWithText("回来看看", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(":smile:", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("查看图片(2)").assertIsDisplayed()
        compose.onNodeWithTag("reply-preview-images-30", useUnmergedTree = true)
            .assertExists().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(listOf("https://linux.do/uploads/original/first.png", "https://linux.do/uploads/original/second.png"),
                gallery.session!!.images.map { it.originalUrl })
            gallery.session!!.select(1)
            assertTrue(gallery.session!!.current.originalUrl.endsWith("second.png"))
        }
    }

    @Test fun ignoredReplyDoesNotExposeAnImageLink() {
        val post = root.copy(id = 30, number = 3, replyTo = 2, cooked = image("private"))
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalIgnoredUsers provides setOf(author.username)) {
                ReplyPreview(PostThread(root, listOf(post)), null, false, true) {}
            }
        } }
        compose.onNodeWithText("回复者：", substring = true).assertIsDisplayed()
        compose.onNodeWithText("内容已忽略", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("reply-preview-images-30", useUnmergedTree = true).assertDoesNotExist()
    }
}
