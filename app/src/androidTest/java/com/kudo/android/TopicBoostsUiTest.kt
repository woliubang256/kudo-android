package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.components.Avatar
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap

class TopicBoostsUiTest {
    @get:Rule val compose = createComposeRule()
    private val author = Member(1, "author", "作者", "", avatarDetailsLoaded = true)
    private val first = Post(1, 1, author, "<p>主帖正文</p>", "", 0, false, null, null, true,
        boosts = listOf(PostBoost(100, author, "<p>支持一下😀</p>")), canBoost = true)
    private val root = first.copy(id = 2, number = 2, cooked = "<p>评论正文</p>", boosts = emptyList())
    private val child = first.copy(id = 3, number = 3, author = author.copy(id = 7, username = "floor_user", name = "楼层昵称"), replyTo = 2, cooked = "<p>楼中楼正文</p>", boosts = listOf(PostBoost(101, author, "<p>回复也可以 Boost</p>")))
    private val detail = TopicDetail(5, "帖子与 Boost", 1, listOf(first, root, child), listOf(1, 2, 3), false)

    private val manyBoosts = (0 until 12).map { index ->
        PostBoost(200 + index, author, "<p>${listOf("赞同", "这个想法值得支持", "继续加油😀", "好")[index % 4]}</p>")
    }
    private fun visibleBoosts(number: Int) = compose.onAllNodes(
        SemanticsMatcher("Boost 短条") {
            it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.matches(Regex("boost-\\d+"))
        } and hasAnyAncestor(hasTestTag("post-boosts-$number")))

    private fun assertTwoBoostRows(number: Int) {
        val chips = visibleBoosts(number).fetchSemanticsNodes()
        assertTrue("默认应隐藏溢出短条", chips.size in 2 until manyBoosts.size)
        assertEquals("按实际排版限制为两行", 2, chips.map { it.boundsInRoot.top.toInt() }.distinct().size)
    }

    private fun checkBoostFolding(settings: DisplaySettings, width: Int, boosts: List<PostBoost> = manyBoosts) {
        compose.setContent { KudoTheme(settings) {
            Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                PostBoosts(first.copy(boosts = boosts), true, {}, {})
            }
        } }
        assertTwoBoostRows(1)
        val expand = compose.onNodeWithContentDescription("展开全部 Boost")
        val parent = compose.onNodeWithTag("post-boosts-1").fetchSemanticsNode().boundsInRoot
        assertEquals("箭头位于右侧", parent.right, expand.fetchSemanticsNode().boundsInRoot.right, 2f)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "boost-fold-${width}.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        expand.performClick()
        visibleBoosts(1).assertCountEquals(manyBoosts.size)
        assertTrue(visibleBoosts(1).fetchSemanticsNodes().map { it.boundsInRoot.top.toInt() }.distinct().size > 2)
        compose.onNodeWithContentDescription("收起 Boost").performScrollTo().performClick()
        assertTwoBoostRows(1)
    }

    @Test fun boostPillsFoldByActualRowsAndToggleAll() = checkBoostFolding(DisplaySettings(), 320)

    @Test fun boostPillsFoldOnNarrowDarkLargeText() =
        checkBoostFolding(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f), 260)

    @Test fun longBoostPillsKeepTwoRowsAndLeaveSpaceForArrow() =
        checkBoostFolding(DisplaySettings(fontScale = 1.3f), 260, manyBoosts.map { it.copy(cooked = "<p>${"字".repeat(16)}</p>") })

    @Test fun expandedBoostsSurviveRestorationAndWindowResize() {
        var width by mutableStateOf(320)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                PostBoosts(first.copy(boosts = manyBoosts), true, {}, {})
            }
        } }
        compose.onNodeWithContentDescription("展开全部 Boost").performClick()
        restoration.emulateSavedInstanceStateRestore()
        visibleBoosts(1).assertCountEquals(manyBoosts.size)
        compose.runOnIdle { width = 260 }
        visibleBoosts(1).assertCountEquals(manyBoosts.size)
        compose.onNodeWithContentDescription("收起 Boost").performScrollTo().performClick()
        assertTwoBoostRows(1)
    }

    @Test fun shortBoostsHaveNoArrowAndPostsExpandIndependently() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                PostBoosts(first, true, {}, {})
                PostBoosts(root.copy(boosts = manyBoosts), true, {}, {})
                PostBoosts(child.copy(boosts = manyBoosts.map { it.copy(id = it.id + 100) }), true, {}, {})
            }
        } }
        compose.onAllNodes(hasContentDescription("展开全部 Boost") and hasAnyAncestor(hasTestTag("post-boosts-1"))).assertCountEquals(0)
        compose.onNode(hasContentDescription("展开全部 Boost") and hasAnyAncestor(hasTestTag("post-boosts-2"))).performClick()
        visibleBoosts(2).assertCountEquals(manyBoosts.size)
        assertTwoBoostRows(3)
        compose.onNode(hasContentDescription("收起 Boost") and hasAnyAncestor(hasTestTag("post-boosts-2"))).performScrollTo().performClick()
        assertTwoBoostRows(2)
    }

    @Test fun boostRocketIsCompactAndGrayInPostAndBottomActions() {
        var density = 1f
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            density = LocalDensity.current.density
            TopicScreen(TopicState(detail.copy(posts = listOf(first)), false), GlassMode.TRANSLUCENT,
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        for ((label, maxSize) in listOf("Boost 此帖子" to 18f, "Boost 主帖" to 20f)) {
            val icon = compose.onNodeWithContentDescription(label, useUnmergedTree = true)
            assertTrue("$label 火箭高度应接近爱心", icon.fetchSemanticsNode().boundsInRoot.height <= maxSize * density)
            val pixels = icon.captureToImage().toPixelMap()
            val colored = buildList {
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    if (color.red < .85f && kotlin.math.abs(color.red - color.green) < .05f) add(color.red)
                }
            }
            assertTrue("$label 灰色应有足够图形像素", colored.size > 20)
            assertTrue("$label 不应呈深黑色", colored.sorted()[colored.size / 2] > .45f)
        }
    }

    @Test fun selectedHeartUsesRedInPostAndBottomActions() {
        val liked = first.copy(liked = true, currentReaction = UserReaction("heart", true))
        compose.setContent { KudoTheme(DisplaySettings()) { Column {
            TopicReactionButton(liked, true, null, {})
            TopicReactionButton(liked, true, null, {}, bottom = true)
        } } }
        for (label in listOf("回应此楼层", "赞主帖")) {
            val pixels = compose.onNodeWithContentDescription(label).captureToImage().toPixelMap()
            var redPixels = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.red > .7f && color.red > color.green * 1.5f && color.red > color.blue * 1.5f) redPixels++
            }
            assertTrue("$label 选中爱心应呈红色", redPixels > 20)
        }
    }

    @Test fun postActionsOnlyShowUsefulControlsAndReplyStillTargetsItsFloor() {
        var replied: Int? = null
        var boosted: Int? = null
        compose.setContent { KudoTheme(DisplaySettings()) { Column {
            for (post in listOf(first, root)) TopicPost(post, detail, TopicState(detail, loading = false), true,
                {}, {}, { replied = it?.number }, {}, {}, {}, {}, boost = { boosted = it.number })
        } } }
        compose.onNodeWithText("正文").assertDoesNotExist()
        compose.onAllNodesWithText("回复").assertCountEquals(2)
        compose.onAllNodesWithText("回复").onLast().performClick()
        compose.onAllNodesWithContentDescription("Boost 此帖子").onLast().performClick()
        compose.runOnIdle { assertEquals(2, replied); assertEquals(2, boosted) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "post-actions-rocket.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun ownMainPostUsesEditInBottomBarInsteadOfBoost() {
        var edits = 0
        val own = first.copy(canEdit = true)
        val ownDetail = detail.copy(posts = listOf(own), stream = listOf(own.id))
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalTopicActions provides TopicActionsController({}, { _, _, _ -> },
                edit = { _, _ -> edits++ })) {
                TopicScreen(
                    state = TopicState(ownDetail, loading = false, viewerId = author.id),
                    glass = GlassMode.TRANSLUCENT,
                    back = {}, retry = {}, more = {}, login = {}, like = {}, bookmark = {},
                    reply = {}, user = {}, image = {}, openLink = {}, share = {},
                )
            }
        } }
        compose.onNodeWithContentDescription("编辑主帖").performClick()
        compose.onNodeWithContentDescription("Boost 主帖").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun ownReplyUsesOneEditActionInsteadOfBoost() {
        var edits = 0
        val ownReply = root.copy(canEdit = true)
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalTopicActions provides TopicActionsController({}, { _, _, _ -> },
                edit = { _, _ -> edits++ })) {
                TopicPost(ownReply, detail, TopicState(detail, loading = false, viewerId = author.id), true,
                    {}, {}, {}, {}, {}, {}, {}, boost = {})
            }
        } }
        compose.onAllNodesWithContentDescription("编辑第 2 楼").assertCountEquals(1).onFirst().performClick()
        compose.onNodeWithContentDescription("Boost 此帖子").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun ownUneditableReplyDoesNotFallBackToBoost() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicPost(root, detail, TopicState(detail, loading = false, viewerId = author.id), true,
                {}, {}, {}, {}, {}, {}, {}, boost = {})
        } }
        compose.onNodeWithContentDescription("编辑第 2 楼").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Boost 此帖子").assertDoesNotExist()
    }

    @Test fun boostAvatarsHideDecorationsWithoutChangingOtherAvatarScenes() {
        val flaired = author.copy(flair = AvatarFlair("测试资质", "battery-quarter"))
        val status = author.copy(id = 2, username = "status", status = UserStatus("smile", "测试状态"))
        var opened = ""
        compose.setContent { KudoTheme(DisplaySettings()) { Column {
            PostBoosts(first.copy(boosts = listOf(PostBoost(100, flaired, "<p>加油😀</p>"),
                PostBoost(102, status, "<p>支持一下</p>"))), true, { opened = it }, {})
            Avatar(flaired, 40.dp)
            Avatar(status, 40.dp)
        } } }
        val chips = hasAnyAncestor(hasTestTag("post-boosts-1"))
        compose.onAllNodes(hasContentDescription("的资质", substring = true) and chips, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("的状态", substring = true) and chips, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("的资质", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("的状态", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("加油😀").assertIsDisplayed()
        compose.onNodeWithTag("boost-100").performClick()
        compose.runOnIdle { assertEquals(author.username, opened) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "boost-plain-avatars.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun compactCounterIncludesEmojiAndExplainsExceededLimits() {
        var raw by mutableStateOf("")
        compose.setContent { KudoTheme(DisplaySettings()) {
            BoostForm(raw, { raw = it }, TopicState(detail, false, boostEmojis = setOf("custom")),
                false, {}, {}, {}, "author")
        } }
        compose.onNodeWithText("0/16字").assertIsDisplayed()
        compose.onNodeWithText("表情", substring = true).assertDoesNotExist()
        val input = compose.onNodeWithTag("boost-input")
        input.performTextInput("加油😀:custom:")
        compose.onNodeWithText("4/16字").assertIsDisplayed()
        compose.onNodeWithText("发送").assertIsEnabled()
        input.performTextReplacement("字".repeat(11) + "😀".repeat(5))
        compose.onNodeWithText("16/16字").assertIsDisplayed()
        compose.onNodeWithText("发送").assertIsEnabled()
        input.performTextReplacement("😀".repeat(6))
        compose.onNodeWithText("6/16字").assertIsDisplayed()
        compose.onNodeWithText("Boost 最多 5 个表情").assertIsDisplayed()
        compose.onNodeWithText("发送").assertIsNotEnabled()
        input.performTextReplacement("字".repeat(17))
        compose.onNodeWithText("17/16字").assertIsDisplayed()
        compose.onNodeWithText("Boost 最多 16 字").assertIsDisplayed()
        compose.onNodeWithText("发送").assertIsNotEnabled()
        input.performTextReplacement("加油")
        compose.onNodeWithText("2/16字").assertIsDisplayed()
        compose.onNodeWithText("Boost 最多", substring = true).assertDoesNotExist()
        compose.onNodeWithText("发送").assertIsEnabled()
    }

    @Test fun boostInputEnforcesBothLimitsAndOnlyClearsAfterConfirmation() {
        var state by mutableStateOf(TopicState(detail, false, viewerId = 9, boostEmojis = emptySet()))
        var requests = 0
        var accepted: (() -> Unit)? = null
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicScreen(state, GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                boost = { post, _, done -> assertEquals(1, post.id); requests++; accepted = done })
        } }
        compose.onNodeWithTag("boost-100").assertExists()
        compose.onNodeWithContentDescription("Boost 主帖").performClick()
        compose.onNodeWithText("boost author").assertIsDisplayed()
        compose.onNodeWithTag("boost-input").assertIsFocused().performTextInput("😀".repeat(6))
        compose.onNodeWithText("boost author").assertDoesNotExist()
        compose.onNodeWithText("发送").assertIsNotEnabled()
        compose.onNodeWithTag("boost-input").performTextReplacement("字".repeat(17))
        compose.onNodeWithText("发送").assertIsNotEnabled()
        compose.onNodeWithTag("boost-input").performTextReplacement("短回复加油")
        compose.onNodeWithText("发送").assertIsEnabled().performClick()
        compose.onNodeWithTag("boost-input").assertTextEquals("短回复加油")
        compose.runOnIdle { assertEquals(1, requests); state = state.copy(busy = setOf(1)) }
        compose.onNodeWithText("发送中…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭 Boost").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(busy = emptySet()); accepted!!.invoke() }
        compose.onNodeWithTag("boost-input").assertDoesNotExist()
        compose.onNodeWithContentDescription("Boost 主帖").performClick()
        compose.onNodeWithTag("boost-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun replyFloorBoostsAndFormWorkInDarkLargeText() {
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            TopicScreen(TopicState(detail, false, viewerId = 9, targetNumber = 3, boostEmojis = emptySet()),
                GlassMode.LIQUID, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("boost-101").performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Boost 此帖子") and hasAnyAncestor(hasTestTag("post-3")))
            .performScrollTo().performClick()
        compose.onNodeWithText("boost floor_user").assertIsDisplayed()
        compose.onNodeWithText("boost 楼层昵称").assertDoesNotExist()
        compose.onNodeWithTag("boost-input").performTextInput("可以继续加油😀")
        compose.onNodeWithText("发送").assertIsEnabled()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "boost-dark-large.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onAllNodesWithTag("sheet-drag-handle").onLast().performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, 650f), 600)
        }
        compose.onNodeWithTag("boost-input").assertDoesNotExist()
        compose.onNodeWithTag("post-3").assertIsDisplayed()
    }
}
