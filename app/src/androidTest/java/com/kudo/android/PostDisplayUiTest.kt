package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PostDisplayUiTest {
    @get:Rule val compose = createComposeRule()
    private val member = Member(8, "member", "带有很长昵称的用户", "", avatarDetailsLoaded = true)
    private val post = Post(42, 2, member, "<p>普通二楼回复</p>", "2026-10-18T01:00:00Z", 0, false, null, null, true)
    private val detail = TopicDetail(1, "测试话题", 1, listOf(post), listOf(post.id), false)

    @Test fun flairDoesNotPaintAClientBackgroundButKeepsTheSiteBackground() {
        var background by mutableStateOf("")
        compose.setContent { KudoTheme(DisplaySettings()) {
            Box(Modifier.size(110.dp).background(Color.Magenta).padding(20.dp)) {
                Avatar(member.copy(flair = AvatarFlair("透明挂件", "battery-quarter", background)), 60.dp)
            }
        } }
        fun sample() = compose.onNodeWithContentDescription("${member.displayName}的资质：透明挂件", useUnmergedTree = true)
            .captureToImage().toPixelMap().let { it[it.width / 2, (it.height * .9f).toInt()] }
        val transparent = sample()
        assertTrue("未配置底色的挂件应透出背景", transparent.red > .9f && transparent.blue > .9f && transparent.green < .1f)
        compose.runOnIdle { background = "00ff00" }
        val configured = sample()
        assertTrue("保留站点明确配置的挂件底色", configured.green > .9f && configured.red < .1f && configured.blue < .1f)
    }

    @Test fun nicknameKeepsGrantedGlyphsAndTitleOnANarrowLargeFontLayout() {
        val decorated = member.copy(title = "社区开发者", moderator = true, headerBadges = listOf(
            PostHeaderBadge(4, "小芽", "初次参与", "seedling", "", 3),
            PostHeaderBadge(5, "观察者", "", "far-eye", "", 2)))
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    TopicPost(post.copy(author = decorated), detail, TopicState(detail, false), true, {}, {}, {}, {}, {}, {}, {})
                }
            }
        } }
        compose.onNodeWithText(member.displayName).assertIsDisplayed()
        compose.onNodeWithText("社区开发者").assertIsDisplayed()
        compose.onNodeWithContentDescription("版主").assertIsDisplayed()
        compose.onNodeWithTag("post-header-badge-4", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("post-header-badge-5", useUnmergedTree = true).assertIsDisplayed()
        val name = compose.onNodeWithText(member.displayName, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithTag("post-header-badge-5", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("昵称必须留有可读宽度", name.width > 20)
        assertTrue("装饰不能挤到昵称上", badge.left >= name.right)
        capture("post-header-decorations.png")
    }

    @Test fun pinRecordsUseCompactRowsWithoutReplyActionsAndNormalSecondFloorStaysNormal() {
        val pin = post.copy(number = 7, postType = 3, actionCode = "pinned_globally.enabled", cooked = "")
        compose.setContent { KudoTheme(DisplaySettings()) { Column {
            TopicPost(pin, detail, TopicState(detail, false), true, {}, {}, {}, {}, {}, {}, {})
            TopicPost(post, detail, TopicState(detail, false), true, {}, {}, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithTag("post-system-action-7").assertIsDisplayed()
        compose.onNodeWithText("全站置顶了此话题").assertIsDisplayed()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("post-system-action-7")) and hasContentDescription("回应此楼层")).assertDoesNotExist()
        compose.onNodeWithText("普通二楼回复").assertIsDisplayed()
        compose.onNodeWithTag("post-system-action-2").assertDoesNotExist()
        capture("post-system-action.png")
    }

    @Test fun summaryUsesTheServerCountAndHeartStillShortTapsAndLongPresses() {
        var likes = 0
        var choices = 0
        var opened = 0
        val reacted = post.copy(likes = 40, reactionCount = 100, reactionsAvailable = true,
            reactions = listOf(PostReaction("heart", 40), PostReaction("laughing", 30), PostReaction("+1", 20), PostReaction("clap", 10)))
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalReactionHistory provides { opened++ }) {
                TopicPost(reacted, detail.copy(validReactions = listOf("heart", "laughing")), TopicState(detail, false), true,
                    { likes++ }, {}, {}, {}, {}, {}, { choices++ })
            }
        } }
        compose.onNodeWithTag("post-reaction-summary-2").assert(hasText("100"))
        compose.onNodeWithTag("post-reaction-2-heart", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("post-reaction-2-laughing", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("post-reaction-2-+1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("post-reaction-2-clap", useUnmergedTree = true).assertDoesNotExist()
        val icons = listOf("heart", "laughing", "+1").map {
            compose.onNodeWithTag("post-reaction-2-$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        icons.zipWithNext().forEach { (previous, next) ->
            assertTrue("回应图标不能重叠", next.left > previous.right)
            assertTrue("回应图标应紧凑排列", next.left - previous.right <= previous.width / 4)
            assertEquals("回应图标应在同一行", previous.center.y, next.center.y, 1f)
        }
        val summary = compose.onNodeWithTag("post-reaction-summary-2").fetchSemanticsNode().boundsInRoot
        val heart = compose.onNodeWithContentDescription("回应此楼层").fetchSemanticsNode().boundsInRoot
        assertTrue("汇总在互动行左侧，点赞保留在右侧", summary.right <= heart.left)
        capture("post-reaction-row.png")
        compose.onNodeWithContentDescription("回应此楼层").assert(hasText("100").not()).performClick()
        compose.onNodeWithContentDescription("回应此楼层").performTouchInput { longClick(durationMillis = 650) }
        compose.onNodeWithTag("post-reaction-summary-2").performClick()
        compose.runOnIdle { assertEquals(1, likes); assertEquals(1, choices); assertEquals(1, opened) }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
