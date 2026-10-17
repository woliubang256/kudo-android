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

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
