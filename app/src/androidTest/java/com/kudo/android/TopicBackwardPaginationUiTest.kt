package com.kudo.android

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TopicBackwardPaginationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun farJumpLoadsEarlierFloorsWithVisibleProgressAndKeepsTheReadingPosition() {
        Fixture().use { fixture ->
            show(fixture)
            jump(fixture)
            compose.onNodeWithTag("topic-posts").performScrollToIndex(1)
            compose.waitUntil(4000) { fixture.vm.state.value.loadingPrevious && fixture.backwardRequests.size == 1 }
            compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
            compose.onNodeWithText("下拉加载前面的楼层").assertDoesNotExist()
            val before = compose.onNodeWithTag("post-1490").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            capture("backward-pagination-loading.png")
            fixture.release.countDown()
            compose.waitUntil(4000) { fixture.vm.state.value.detail?.posts?.firstOrNull()?.number == 1470 }
            val after = compose.onNodeWithTag("post-1490").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("前插后保持原楼层像素位置", before.top, after.top, 3f)
            assertEquals((1470..1549).toList(), fixture.vm.state.value.detail!!.posts.map(Post::number))
            compose.onNodeWithTag("topic-posts").performScrollToIndex(1)
            compose.waitUntil(4000) { fixture.vm.state.value.detail?.posts?.firstOrNull()?.number == 1450 }
            assertEquals(2, fixture.backwardRequests.size)
        }
    }

    @Test fun thePreviousPagePromptTriggersLoadingBeforeAnyPostEntersTheViewport() {
        Fixture(title = List(40) { "用于检查顶部边界的长话题标题" }.joinToString("\n")).use { fixture ->
            show(fixture)
            jump(fixture)
            val viewport = compose.onNodeWithTag("topic-posts").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("topic-posts").performScrollToIndex(0)
            val titleNode = compose.onNodeWithText(fixture.title, useUnmergedTree = true).fetchSemanticsNode()
            val titleBottom = titleNode.positionInRoot.y + titleNode.size.height
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            compose.onNodeWithTag("topic-posts").performSemanticsAction(SemanticsActions.ScrollBy) {
                // The title starts below the measured overlay toolbar, inside the full-height list.
                it(0f, titleBottom + 24f * density - viewport.bottom + 120f)
            }
            compose.onNodeWithTag("post-1490").assertIsNotDisplayed()
            compose.waitUntil(3000) { fixture.vm.state.value.loadingPrevious && fixture.backwardRequests.size == 1 }
            compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
            fixture.release.countDown()
            compose.waitUntil(4000) { fixture.vm.state.value.detail?.posts?.firstOrNull()?.number == 1470 }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("提示停留在视口时不能连续补齐整个间隔", 1, fixture.backwardRequests.size) }
        }
    }

    @Test fun draggingWhileThePreviousPageArrivesDoesNotBlockTheNextPage() {
        Fixture(pauseLaterPrevious = true).use { fixture ->
            show(fixture)
            jump(fixture)
            val list = compose.onNodeWithTag("topic-posts")
            list.performScrollToIndex(1)
            compose.waitUntil(4000) { fixture.vm.state.value.loadingPrevious && fixture.backwardRequests.size == 1 }
            list.performTouchInput { down(center); moveBy(Offset(0f, 100f), delayMillis = 100) }
            val before = compose.onNodeWithTag("post-1490").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            try {
                fixture.release.countDown()
                compose.waitUntil(4000) { fixture.vm.state.value.detail?.posts?.firstOrNull()?.number == 1470 }
                compose.waitForIdle()
                val after = compose.onNodeWithTag("post-1490").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals("拖动中前插仍保持正在看的楼层位置", before.top, after.top, 3f)
                assertEquals("拖动中前插不能误触下一页", 1, fixture.backwardRequests.size)
            } finally {
                list.performTouchInput { up() }
            }
            fixture.releaseNext.countDown()
            list.performScrollToIndex(1)
            try {
                compose.waitUntil(4000) { fixture.vm.state.value.detail?.posts?.firstOrNull()?.number == 1450 }
            } catch (e: Throwable) {
                throw AssertionError("拖动后首楼=${fixture.vm.state.value.detail?.posts?.firstOrNull()?.number}，前页请求=${fixture.backwardRequests.size}", e)
            }
            assertEquals(2, fixture.backwardRequests.size)
        }
    }

    @Test fun anUnavailablePreviousPageDoesNotLeavePaginationBlocked() {
        Fixture(emptyFirstPrevious = true).use { fixture ->
            show(fixture)
            jump(fixture)
            compose.onNodeWithTag("topic-posts").performScrollToIndex(1)
            compose.waitUntil(4000) { fixture.vm.state.value.loadingPrevious && fixture.backwardRequests.size == 1 }
            fixture.release.countDown()
            compose.waitUntil(4000) { fixture.vm.state.value.detail?.posts?.firstOrNull()?.number == 1450 }
            assertEquals(2, fixture.backwardRequests.size)
            assertTrue(fixture.vm.state.value.detail!!.stream.none { it in (1470..1489).map { number -> 10000 + number * 3 } })
        }
    }

    @Test fun interruptingTheJumpAnimationDoesNotDisableBackwardLoading() {
        Fixture().use { fixture ->
            show(fixture)
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnUiThread { fixture.vm.jumpTo(1520) }
                compose.waitUntil(4000) { fixture.vm.state.value.targetRevision == 1L }
                compose.mainClock.advanceTimeByFrame()
                SystemClock.sleep(80)
                compose.mainClock.advanceTimeBy(64)
                compose.onNodeWithTag("topic-posts").performScrollToIndex(1)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.onNodeWithTag("post-1490").assertIsDisplayed()
            compose.waitUntil(3000) { fixture.vm.state.value.loadingPrevious && fixture.backwardRequests.size == 1 }
            compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        }
    }

    @Test fun aFailedPreviousPageDoesNotBlockForwardLoadingAndCanRetry() {
        Fixture(failFirstPrevious = true).use { fixture ->
            show(fixture)
            jump(fixture)
            compose.onNodeWithTag("topic-posts").performScrollToIndex(1)
            compose.waitUntil(4000) { fixture.vm.state.value.previousError != null }
            compose.onNodeWithText("重新加载").assertIsDisplayed()
            compose.runOnIdle { assertEquals(1, fixture.backwardRequests.size) }
            // The footer disappears as soon as it triggers loading; scroll to its current index once.
            compose.onNodeWithTag("topic-posts").performScrollToIndex(fixture.vm.state.value.detail!!.posts.size + 2)
            compose.waitUntil(4000) { fixture.vm.state.value.detail?.posts?.lastOrNull()?.number == 1569 }
            compose.onNodeWithTag("topic-posts").performScrollToNode(hasText("重新加载"))
            fixture.release.countDown()
            compose.onNodeWithText("重新加载").performClick()
            compose.waitUntil(4000) { fixture.vm.state.value.detail?.posts?.firstOrNull()?.number == 1470 }
            compose.runOnIdle {
                assertNull(fixture.vm.state.value.previousError)
                assertEquals(2, fixture.backwardRequests.size)
            }
        }
    }

    private fun show(fixture: Fixture) {
        compose.setContent {
            val state by fixture.vm.state.collectAsState()
            KudoTheme(DisplaySettings()) {
                TopicScreen(state, GlassMode.TRANSLUCENT, {}, fixture.vm::refresh, fixture.vm::more,
                    {}, {}, {}, {}, {}, {}, {}, {}, jumpTo = fixture.vm::jumpTo, previous = fixture.vm::previous)
            }
        }
        compose.waitUntil(4000) { !fixture.vm.state.value.loading }
        compose.waitForIdle()
    }

    private fun jump(fixture: Fixture) {
        compose.runOnUiThread { fixture.vm.jumpTo(1520) }
        compose.waitUntil(4000) { fixture.vm.state.value.targetRevision == 1L }
        compose.onNodeWithTag("post-1520").assertIsDisplayed()
        compose.runOnIdle { assertTrue(fixture.backwardRequests.isEmpty()) }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class Fixture(val title: String = "请不要把互联网上的戾气带来这里！", val failFirstPrevious: Boolean = false,
        val emptyFirstPrevious: Boolean = false, val pauseLaterPrevious: Boolean = false) : AutoCloseable {
        val release = CountDownLatch(1)
        val releaseNext = CountDownLatch(1)
        val backwardRequests = CopyOnWriteArrayList<List<Int>>()
        private val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            var status = 200
            val body = when (request.url.encodedPath) {
                "/t/5.json" -> topic(1..20)
                "/t/5/1520.json" -> topic(1510..1529)
                "/t/5/posts.json" -> {
                    val numbers = request.url.queryParameterValues("post_ids[]").map { (it!!.toInt() - 10000) / 3 }
                    if (numbers.first() >= 1400 && numbers.last() < 1490) {
                        backwardRequests += numbers
                        if (failFirstPrevious && backwardRequests.size == 1) status = 503
                        else {
                            check(release.await(10, TimeUnit.SECONDS)) { "fixture page was never released" }
                            if (pauseLaterPrevious && backwardRequests.size > 1) check(releaseNext.await(10, TimeUnit.SECONDS)) { "fixture next page was never released" }
                        }
                    }
                    val posts = if (emptyFirstPrevious && numbers == backwardRequests.firstOrNull()) emptyList() else numbers.map(::post)
                    JSONObject().put("post_stream", JSONObject().put("posts", JSONArray(posts))).toString()
                }
                else -> { status = 404; "{}" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val vm: TopicViewModel

        init {
            val cookies = object : SessionCookies {
                override fun get(url: String) = ""
                override fun set(url: String, cookie: String) { }
            }
            val repository = CommunityRepository(DiscourseClient(cookies, http = http))
            var created: TopicViewModel? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync { created = TopicViewModel(repository, 5, viewerId = 1) }
            vm = checkNotNull(created)
        }

        private fun post(number: Int) = JSONObject().put("id", 10000 + number * 3).put("post_number", number)
            .put("user_id", 1).put("username", "fixture").put("name", "离线作者")
            .put("cooked", "<p>${if (number == 1490) "支持支持" else "正文第 $number 楼"}</p>")

        private fun topic(numbers: IntRange) = JSONObject().put("id", 5).put("title", title).put("category_id", 1)
            .put("highest_post_number", 1600).put("post_stream", JSONObject()
                .put("stream", JSONArray((1..1600).map { 10000 + it * 3 })).put("posts", JSONArray(numbers.map(::post)))).toString()

        override fun close() {
            release.countDown()
            releaseNext.countDown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.viewModelScope.cancel() }
            http.dispatcher.cancelAll()
        }
    }
}
