package com.kudo.android

import android.os.SystemClock
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.topic.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TopicReadingUiTest {
    @get:Rule val compose = createComposeRule()
    private val author = Member(2, "test", "离线作者", "")
    private fun post(number: Int, html: String = "<p>实际可见的正文</p>") = Post(number + 100, number, author, html, "", 0, false, null, null, false)

    @Test fun unreadDotsRespectServerStateAndFadeGraduallyAfterLocalReading() {
        val posts = listOf(post(1).copy(read = false), post(2).copy(read = false), post(3).copy(read = true), post(4))
        val detail = TopicDetail(12, "蓝点状态", 1, posts, posts.map(Post::id), false)
        var state by mutableStateOf(TopicState(detail, false, viewerId = 7))
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicScreen(state, GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, readingEnabled = false)
        } }
        compose.onNodeWithTag("unread-post-1").assertDoesNotExist()
        compose.onNodeWithTag("unread-post-2").assertExists()
        compose.onNodeWithTag("post-floor-number-2").assertTextEquals("#2").assertIsDisplayed()
        compose.onNodeWithTag("unread-post-3").assertDoesNotExist()
        compose.onNodeWithTag("unread-post-4").assertDoesNotExist()
        capture("post-unread-blue.png")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(readNumbers = setOf(2)) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("unread-post-2").assertExists()
        capture("post-unread-fading.png")
        compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithTag("unread-post-2").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { state = state.copy(viewerId = null) }
        compose.onNodeWithTag("unread-post-2").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-tests/$name")
        output.parentFile!!.mkdirs()
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test fun floorWatermarkStaysNearTheRightEdgeOfItsPost() {
        val posts = listOf(post(1), post(2))
        val detail = TopicDetail(12, "floor", 1, posts, posts.map(Post::id), false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicScreen(TopicState(detail, false, viewerId = 7), GlassMode.TRANSLUCENT,
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        val postBounds = compose.onNodeWithTag("post-2").fetchSemanticsNode().boundsInRoot
        val floorBounds = compose.onNodeWithTag("post-floor-number-2").fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue("floor marker should ignore the 16dp content padding", postBounds.right - floorBounds.right <= 5f * density)
        assertTrue("floor marker should be at the outer top edge", floorBounds.top - postBounds.top <= 3f * density)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("post-floor-number-2").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, layouts.single().layoutInput.style.fontWeight)
        assertTrue(layouts.single().layoutInput.style.fontSize.value <= 12f)
    }

    @Test fun realExposureMarksOnlyOriginalBodyAndThreadPreviewStaysUnreadUntilNavigated() {
        val batches = mutableListOf<TopicReadingBatch>()
        val root = post(8).copy(read = false)
        val child = post(9).copy(replyTo = 8, read = false)
        val detail = TopicDetail(12, "实际读到", 1, listOf(root), listOf(root.id, child.id), false)
        var state by mutableStateOf(TopicState(detail, false, viewerId = 7, related = listOf(child)))
        var blocked by mutableStateOf(true)
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalReadingBlocked provides blocked) {
                TopicScreen(state, GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onReadingBatch = { batches += it }, onPostsRead = { state = state.copy(readNumbers = state.readNumbers + it) },
                    jumpTo = {
                        blocked = true // 到达原楼层后先暂停计时，检查未读状态再继续曝光。
                        state = state.copy(detail = detail.copy(posts = listOf(root, child)), targetNumber = it, targetRevision = state.targetRevision + 1)
                    })
            }
        } }
        compose.waitForIdle(); SystemClock.sleep(1300)
        compose.runOnIdle { assertTrue(state.readNumbers.isEmpty()); blocked = false }
        compose.waitUntil(4000) { 8 in state.readNumbers }
        compose.runOnIdle { assertEquals(setOf(8), state.readNumbers); blocked = true }
        compose.waitUntil(3000) { batches.isNotEmpty() }
        compose.onNodeWithText("查看 1 条回复").performClick()
        compose.runOnIdle { blocked = false }
        compose.waitForIdle(); SystemClock.sleep(1300)
        compose.runOnIdle { assertEquals(setOf(8), state.readNumbers); blocked = true }
        compose.onNodeWithTag("thread-jump-9").assertIsNotEnabled()
        compose.runOnIdle { blocked = false }
        compose.onNodeWithTag("thread-jump-9").performScrollTo().performClick()
        compose.onNodeWithTag("post-9").assertIsDisplayed()
        compose.onNodeWithTag("unread-post-9").assertIsDisplayed()
        compose.runOnIdle { blocked = false }
        compose.waitUntil(4000) { 9 in state.readNumbers }
        compose.runOnIdle { blocked = true; assertEquals(setOf(8, 9), state.readNumbers) }
    }

    @Test fun readOnlyDiscussionDoesNotAddReadingTimeForCachedReplies() {
        var active by mutableStateOf(true)
        val batches = mutableListOf<TopicReadingBatch>()
        val root = post(8)
        val child = post(9).copy(replyTo = 8)
        val detail = TopicDetail(12, "离线详情", 1, listOf(root), listOf(root.id, child.id), false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicScreen(TopicState(detail, loading = false, viewerId = 7, related = listOf(child)), GlassMode.TRANSLUCENT,
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, readingEnabled = active, onReadingBatch = { batches += it })
        } }
        compose.waitForIdle(); SystemClock.sleep(1300)
        compose.runOnIdle { active = false }
        compose.waitUntil(3000) { batches.isNotEmpty() }
        assertEquals(setOf(8), batches.flatMap { it.timings.keys }.toSet())
        compose.onNodeWithText("查看 1 条回复").performClick()
        compose.waitForIdle()
        compose.runOnIdle { active = true }
        compose.waitForIdle(); SystemClock.sleep(1300)
        compose.runOnIdle { active = false }
        compose.runOnIdle { assertFalse(batches.any { 9 in it.timings }) }
    }

    @Test fun disposingAnOldViewerKeepsItsOriginalSubmissionIdentity() {
        var viewer by mutableIntStateOf(7)
        val batches = mutableListOf<Pair<Int, TopicReadingBatch>>()
        compose.setContent { KudoTheme(DisplaySettings()) {
            val boundViewer = viewer
            TopicReadingEnvironment(12, boundViewer, true, { batches += boundViewer to it }) {
                PostReadingViewport { viewport ->
                    Column(Modifier.fillMaxSize().then(viewport)) {
                        PostReadingContent(if (boundViewer == 7) 8 else 9, true) {
                            androidx.compose.material3.Text("离线正文")
                        }
                    }
                }
            }
        } }
        compose.waitForIdle(); SystemClock.sleep(1300)
        compose.runOnIdle { viewer = 10 }
        compose.waitUntil(3000) { batches.isNotEmpty() }
        assertEquals(7, batches.first().first)
        assertEquals(setOf(8), batches.first().second.timings.keys)
    }

    @Test fun ignoredBodiesGuestsAndBlockingSheetsNeverProduceReading() {
        var viewer by mutableStateOf<Int?>(7)
        var ignored by mutableStateOf(setOf("test"))
        var blocked by mutableStateOf(false)
        val batches = mutableListOf<TopicReadingBatch>()
        val root = post(1)
        val detail = TopicDetail(12, "离线话题", 1, listOf(root), listOf(root.id), false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalIgnoredUsers provides ignored, LocalReadingBlocked provides blocked) {
                TopicReadingEnvironment(12, viewer, true, { batches += it }) {
                    PostReadingViewport { viewport ->
                        LazyColumn(Modifier.fillMaxSize().then(viewport)) {
                            item { TopicPost(root, detail, TopicState(detail, loading = false), true, {}, {}, {}, {}, {}, {}, {}) }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle(); SystemClock.sleep(1300)
        compose.runOnIdle { viewer = null; ignored = emptySet() }
        compose.waitForIdle(); SystemClock.sleep(1300)
        compose.runOnIdle { viewer = 7; blocked = true }
        compose.waitForIdle(); SystemClock.sleep(1300)
        compose.runOnIdle { assertTrue(batches.isEmpty()); blocked = false }
        compose.waitForIdle(); SystemClock.sleep(1300)
        compose.runOnIdle { blocked = true }
        compose.waitUntil(3000) { batches.isNotEmpty() }
        assertEquals(setOf(1), batches.flatMap { it.timings.keys }.toSet())
    }

    @Test fun actualBodyExposureProducesOnlyItsFloorAndPausesUnderOverlays() {
        var active by mutableStateOf(true)
        val batches = mutableListOf<TopicReadingBatch>()
        val root = post(8)
        val preview = post(9)
        val detail = TopicDetail(12, "离线话题", 1, listOf(root, preview), listOf(root.id, preview.id), false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReadingEnvironment(12, 7, active, { batches += it }) {
                PostReadingViewport { viewport ->
                    LazyColumn(Modifier.fillMaxWidth().height(350.dp).then(viewport)) {
                        item { TopicPost(root, detail, TopicState(detail, loading = false), true, {}, {}, {}, {}, {}, {}, {}) }
                        item { ReplyPreview(PostThread(root, listOf(preview)), null, false, true) {} }
                    }
                }
            }
        } }
        compose.waitForIdle()
        SystemClock.sleep(1600)
        compose.runOnIdle { active = false }
        compose.waitUntil(3000) { batches.isNotEmpty() }
        assertEquals(setOf(8), batches.flatMap { it.timings.keys }.toSet())
        val before = batches.size
        SystemClock.sleep(1600)
        compose.runOnIdle { assertEquals(before, batches.size) }
    }

    @Test fun bodyBehindTheGlassToolbarIsExcludedFromReadingExposure() {
        var inset by mutableStateOf(80.dp)
        var active by mutableStateOf(true)
        val read = mutableSetOf<Int>()
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReadingEnvironment(12, 7, active, {}, { read += it }) {
                PostReadingViewport(topInset = inset) { viewport ->
                    Column(Modifier.fillMaxWidth().height(240.dp).then(viewport)) {
                        PostReadingContent(1, true) { Box(Modifier.fillMaxWidth().height(60.dp)) }
                    }
                }
            }
        } }
        compose.waitForIdle()
        SystemClock.sleep(1300)
        compose.runOnIdle { assertTrue("顶部玻璃遮挡的正文不能标读", read.isEmpty()); active = false; inset = 0.dp }
        compose.runOnIdle { active = true }
        compose.waitUntil(4000) { 1 in read }
    }

    @Test fun longBodyIsNotReadFromItsHeaderButCountsWhenItsEndIsVisible() {
        var active by mutableStateOf(true)
        val batches = mutableListOf<TopicReadingBatch>()
        val root = post(1, (1..70).joinToString("") { "<p>长正文第 $it 段，需要滚动阅读。</p>" })
        val detail = TopicDetail(12, "长文", 1, listOf(root), listOf(root.id), false)
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicReadingEnvironment(12, 7, active, { batches += it }) {
                PostReadingViewport { viewport ->
                    LazyColumn(Modifier.fillMaxWidth().height(350.dp).then(viewport).testTag("reading-scroll")) {
                        item { TopicPost(root, detail, TopicState(detail, loading = false), true, {}, {}, {}, {}, {}, {}, {}) }
                    }
                }
            }
        } }
        compose.waitForIdle()
        SystemClock.sleep(1600)
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        assertTrue("仅看到长正文开头不能标读", batches.isEmpty())
        compose.onNodeWithContentDescription("Boost 此帖子").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        SystemClock.sleep(1600)
        compose.runOnIdle { active = false }
        compose.waitUntil(3000) { batches.isNotEmpty() }
        assertEquals(setOf(1), batches.flatMap { it.timings.keys }.toSet())
    }
}
