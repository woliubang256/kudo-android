package com.kudo.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.account.PersonalTopicsScreen
import com.kudo.android.ui.account.PersonalTopicsViewModel
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PersonalHistoryUiTest {
    @get:Rule val compose = createComposeRule()
    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(
        androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab))
    private val requests = AtomicInteger()
    private val visited = AtomicBoolean(false)
    private var manyTopics = false
    private var vm: PersonalTopicsViewModel? = null
    private lateinit var owner: FixtureOwner
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests.incrementAndGet()
        if (request.url.encodedPath == "/user_actions.json" && request.url.queryParameter("filter") == "5") {
            val body = """{"user_actions":[{"id":8,"topic_id":7,"post_number":4,"title":"回复所在的主题","excerpt":"我的回复","action_type":5}]}"""
            return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        val topics = if (manyTopics) (1..40).joinToString(",", "[", "]") { """{"id":$it,"title":"话题$it"}""" }
        else if (request.url.queryParameter("page") == "1")
            """[{"id":3,"title":"更早的历史"}]"""
        else if (visited.get()) """[{"id":2,"title":"刚浏览的新帖子"},{"id":1,"title":"旧历史"}]"""
        else """[{"id":1,"title":"旧历史"}]"""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("""{"topic_list":{"topics":$topics,"more_topics_url":${if (manyTopics) "null" else "\"/read.json?page=1\""}}}""".toResponseBody("application/json".toMediaType())).build()
    }.build()

    @After fun dispose() {
        compose.runOnIdle { vm?.viewModelScope?.cancel(); owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun show(destination: AccountDestination) {
        compose.runOnIdle {
            owner = FixtureOwner()
            val repository = CommunityRepository(DiscourseClient(object : SessionCookies {
                override fun get(url: String) = ""
                override fun set(url: String, cookie: String) {}
            }, http = http))
            vm = PersonalTopicsViewModel(repository, "fixture", destination)
        }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            KudoTheme(DisplaySettings()) {
                PersonalTopicsScreen(vm!!, emptyList(), TopicListStyle.TITLE, {}, {}, { _, _ -> }, {}, {})
            }
        } }
        compose.waitUntil(5000) { vm!!.topics.isNotEmpty() && !vm!!.loading }
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
