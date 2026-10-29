package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.account.PersonalTopicsScreen
import com.kudo.android.ui.account.PersonalTopicsViewModel
import com.kudo.android.ui.components.QuickScrollToTopHost
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PersonalHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(
        androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab))
    private val requests = AtomicInteger()
    private val visited = AtomicBoolean(false)
    private var manyTopics = false
    private var manyReplies = false
    private var emptyTopics = false
    private var vm: PersonalTopicsViewModel? = null
    private lateinit var owner: FixtureOwner
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests.incrementAndGet()
        if (request.url.encodedPath == "/user_actions.json" && request.url.queryParameter("filter") == "5") {
            val actions = if (manyReplies) (1..40).joinToString(",") {
                """{"id":$it,"topic_id":${100 + it},"post_number":4,"title":"回复主题$it","excerpt":"我的回复$it","action_type":5}"""
            } else """{"id":8,"topic_id":7,"post_number":4,"title":"回复所在的主题","excerpt":"我的回复","action_type":5}"""
            val body = """{"user_actions":[$actions]}"""
            return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val topics = if (emptyTopics) "[]" else if (manyTopics) (1..40).joinToString(",", "[", "]") { """{"id":$it,"title":"话题$it"}""" }
        else if (request.url.queryParameter("page") == "1")
            """[{"id":3,"title":"更早的历史"}]"""
        else if (visited.get()) """[{"id":2,"title":"刚浏览的新帖子"},{"id":1,"title":"旧历史"}]"""
        else """[{"id":1,"title":"旧历史"}]"""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"topic_list":{"topics":$topics,"more_topics_url":${if (manyTopics || emptyTopics) "null" else "\"/read.json?page=1\""}}}""".toResponseBody("application/json".toMediaType())).build()
    }.build()

    @After fun dispose() {
        compose.runOnIdle { vm?.viewModelScope?.cancel(); owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun show(destination: AccountDestination, narrow: Boolean = false,
        message: () -> Unit = {}, drafts: () -> Unit = {}, quickScroll: Boolean = false) {
        compose.runOnIdle {
            owner = FixtureOwner()
            val repository = CommunityRepository(DiscourseClient(object : SessionCookies {
                override fun get(url: String) = ""
                override fun set(url: String, cookie: String) {}
            }, http = http))
            vm = PersonalTopicsViewModel(repository, "fixture", destination)
        }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            KudoTheme(DisplaySettings(theme = if (narrow) ThemeMode.DARK else ThemeMode.LIGHT, fontScale = if (narrow) 1.3f else 1f)) {
                Box(if (narrow) Modifier.width(280.dp) else Modifier) {
                    if (quickScroll) QuickScrollToTopHost {
                        PersonalTopicsScreen(vm!!, emptyList(), TopicListStyle.TITLE, {}, {}, { _, _ -> }, {}, message, drafts)
                    } else PersonalTopicsScreen(vm!!, emptyList(), TopicListStyle.TITLE, {}, {}, { _, _ -> }, {}, message, drafts)
                }
            }
        } }
        compose.waitUntil(5000) { vm!!.page(vm!!.pageKey).loaded && !vm!!.loading }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun reverse(key: String) = compose.onNodeWithTag("personal-list-$key").performTouchInput {
        swipe(Offset(center.x, height * .55f), Offset(center.x, height * .8f), durationMillis = 700)
    }

    @Test fun mailboxTitleCollapsesWithTabsPinnedAndExpandsBeforeReturningToTheTop() {
        manyTopics = true
        var composed = 0
        show(AccountDestination.MESSAGES, message = { composed++ })
        val original = tab("收件箱").fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNodeWithTag("personal-list-inbox")
        repeat(2) { list.performTouchInput { swipeUp(durationMillis = 700) } }
        compose.onNodeWithText("私信").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("写私信").assertIsNotDisplayed()
        val pinned = tab("收件箱").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue(pinned < original - 40f)
        list.performTouchInput { swipeUp(durationMillis = 700) }
        assertEquals(pinned, tab("收件箱").fetchSemanticsNode().boundsInRoot.top, 1f)
        capture("mailboxes-tabs-pinned.png")
        reverse("inbox")
        compose.onNodeWithText("私信").assertIsDisplayed()
        compose.onNodeWithText("话题1").assertDoesNotExist()
        assertEquals(original, tab("收件箱").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithContentDescription("写私信").performClick()
        assertEquals(1, composed)
        compose.runOnIdle { assertEquals(1, requests.get()) }
    }

    @Test fun topicsAndRepliesKeepPinnedTabsAndTheirOwnPositionsAcrossSwipeAndClick() {
        manyTopics = true; manyReplies = true
        var openedDrafts = 0
        show(AccountDestination.TOPICS, drafts = { openedDrafts++ })
        val original = tab("话题").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("personal-list-TOPICS").performTouchInput { swipeUp(durationMillis = 700) }
        compose.onNodeWithText("话题/回复").assertIsNotDisplayed()
        val pinned = tab("话题").fetchSemanticsNode().boundsInRoot.top
        assertTrue(pinned < original - 40f)
        compose.onNodeWithTag("personal-list-TOPICS").performScrollToIndex(15)
        compose.onNodeWithTag("personal-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.waitUntil(5000) { vm!!.replies.size == 40 && !vm!!.loading }
        tab("回复").assertIsSelected()
        assertEquals(pinned, tab("回复").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("personal-list-REPLIES").performTouchInput { swipeUp(durationMillis = 700) }
        capture("personal-replies-tabs-pinned.png")
        tab("话题").performClick()
        compose.onNodeWithText("话题16").assertIsDisplayed()
        assertEquals(pinned, tab("话题").fetchSemanticsNode().boundsInRoot.top, 1f)
        reverse("TOPICS")
        compose.onNodeWithText("话题/回复").assertIsDisplayed()
        compose.onNodeWithText("话题1").assertDoesNotExist()
        compose.onNodeWithContentDescription("我的草稿").performClick()
        assertEquals(1, openedDrafts)
        compose.runOnIdle { assertEquals(2, requests.get()) }
    }

    @Test fun emptyMailboxCollapsesAndExpandsInADarkNarrowLargeTextWindow() {
        emptyTopics = true
        show(AccountDestination.MESSAGES, narrow = true)
        val original = tab("收件箱").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("personal-list-inbox").performTouchInput { swipeUp(durationMillis = 700) }
        compose.onNodeWithText("私信").assertIsNotDisplayed()
        tab("收件箱").assertIsDisplayed()
        capture("mailboxes-empty-dark-large-pinned.png")
        compose.onNodeWithTag("personal-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.waitUntil(5000) { vm!!.mailbox == "sent" && !vm!!.loading }
        tab("已发送").assertIsSelected()
        compose.onNodeWithText("这里还没有内容").assertIsDisplayed()
        reverse("sent")
        compose.onNodeWithText("私信").assertIsDisplayed()
        assertEquals(original, tab("已发送").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun mailboxQuickReturnRestoresItsTitleFirstItemAndRefreshAction() {
        manyTopics = true
        show(AccountDestination.MESSAGES, quickScroll = true)
        val original = tab("收件箱").fetchSemanticsNode().boundsInRoot.top
        val list = compose.onNodeWithTag("personal-list-inbox")
        list.performScrollToIndex(20)
        list.performTouchInput { swipeUp(durationMillis = 700) }
        compose.onNodeWithText("私信").assertIsNotDisplayed()
        reverse("inbox")
        compose.onNodeWithTag("quick-scroll-to-top").assertIsDisplayed().performClick()
        compose.onNodeWithText("私信").assertIsDisplayed()
        compose.onNodeWithText("话题1").assertIsDisplayed()
        assertEquals(original, tab("收件箱").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithContentDescription("刷新").performClick()
        compose.waitUntil(5000) { requests.get() == 2 && !vm!!.loading }
        compose.onNodeWithText("话题1").assertIsDisplayed()
        compose.onNodeWithTag("quick-scroll-to-top").assertDoesNotExist()
    }

    @Test fun historyKeepsItsFixedTitleWhenItsListScrolls() {
        manyTopics = true
        show(AccountDestination.HISTORY)
        val original = compose.onNodeWithText("浏览历史").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("personal-list-inbox").performTouchInput { swipeUp(durationMillis = 700) }
        compose.onNodeWithText("浏览历史").assertIsDisplayed()
        assertEquals(original, compose.onNodeWithText("浏览历史").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("刷新").assertIsDisplayed()
    }

    @Test fun historyResumeRefreshesServerResultsWithoutDuplicatingInitialRequest() {
        show(AccountDestination.HISTORY)
        compose.runOnIdle { assertEquals(1, requests.get()); owner.lifecycle.currentState = Lifecycle.State.STARTED }
        visited.set(true)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(3000) { vm!!.topics.firstOrNull()?.id == 2 && !vm!!.loading }
        compose.onNodeWithText("刚浏览的新帖子").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, requests.get()) }
    }

    @Test fun otherPersonalListsKeepTheirLoadedPaginationWhenResuming() {
        show(AccountDestination.TOPICS)
        compose.runOnIdle { vm!!.load(true) }
        compose.waitUntil(5000) { vm!!.topics.size == 2 && !vm!!.loading }
        compose.runOnIdle { assertEquals(2, requests.get()); owner.lifecycle.currentState = Lifecycle.State.STARTED }
        visited.set(true)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle {
            assertEquals(2, requests.get())
            assertEquals(listOf(1, 3), vm!!.topics.map { it.id })
        }
    }

    @Test fun topicsPageOffersRepliesTabAndLoadsReplyActions() {
        show(AccountDestination.TOPICS)
        compose.onNodeWithText("回复").performClick()
        compose.waitUntil(5000) { vm!!.replies.size == 1 && !vm!!.loading }
        compose.onNodeWithText("回复所在的主题").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, requests.get())
            assertEquals(7, vm!!.replies.single().topicId)
            assertEquals(4, vm!!.replies.single().number)
        }
    }

    @Test fun swipingTopicsKeepsPositionsAndReusesBothLoadedPages() {
        manyTopics = true
        show(AccountDestination.TOPICS)
        compose.onNodeWithTag("personal-list-TOPICS").performScrollToIndex(15)
        compose.onNodeWithTag("personal-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.waitUntil(5000) { vm!!.replies.size == 1 && !vm!!.loading }
        tab("回复").assertIsSelected()
        compose.onNodeWithTag("personal-pager").performTouchInput { swipeRight(durationMillis = 600) }
        tab("话题").assertIsSelected()
        compose.onNodeWithText("话题16").assertIsDisplayed()
        tab("回复").performClick()
        compose.onNodeWithText("回复所在的主题").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, requests.get()) }
    }

    @Test fun mailboxesSwipeAndClickWithoutReloadingVisitedMailboxes() {
        show(AccountDestination.MESSAGES)
        for (mailbox in listOf("sent", "archive")) {
            compose.onNodeWithTag("personal-pager").performTouchInput { swipeLeft(durationMillis = 600) }
            compose.waitUntil(5000) { vm!!.mailbox == mailbox && !vm!!.loading }
        }
        compose.onNodeWithText("归档").assertIsSelected()
        compose.onNodeWithTag("personal-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithText("已发送").assertIsSelected()
        compose.onNodeWithText("收件箱").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(3, requests.get()) }
    }

    private class FixtureOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    }
}
