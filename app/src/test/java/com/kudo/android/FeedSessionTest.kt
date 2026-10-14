package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.feed.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class FeedSessionTest {
    private val alice = Member(1, "alice", "甲", "")
    private val bob = Member(2, "bob", "乙", "")
    private fun page(id: Int, next: String? = null) = MockResponse().setBody(
        """{"topic_list":{"topics":[{"id":$id,"title":"话题$id"}],"more_topics_url":${next?.let { "\"$it\"" } ?: "null"}}}""")
    private suspend fun FeedViewModel.idle() = withContext(Dispatchers.Default) {
        withTimeout(5000) { viewModelScope.coroutineContext.job.children.toList().joinAll() }
    }

    @Test fun accountSwitchResetsFiltersDataAndCountsAndRejectsGuestPersonalRequests() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/site.json" -> MockResponse().setBody("""{"categories":[{"id":8,"name":"分类"}]}""")
                "/u/alice/topic-tracking-state.json" -> MockResponse().setBody("""[{"topic_id":9,"last_read_post_number":null,"created_in_new_period":true}]""")
                "/u/bob/topic-tracking-state.json" -> MockResponse().setBody("[]")
                "/latest.json" -> page(10)
                else -> page(20, "/unread.json?page=1")
            }
        }
        server.start()
        val vm = FeedViewModel(CommunityRepository(DiscourseClient(NoCookies, server.url("/"))))
        try {
            vm.idle()
            val guestRequests = server.requestCount
            listOf("unread", "new", "posted", "read", "bookmarks", "categories", "invalid").forEach(vm::select)
            assertEquals(guestRequests, server.requestCount)
            assertEquals("latest", vm.state.value.filter)
            vm.setUser(alice)
            assertEquals("unread", vm.state.value.filter)
            assertTrue(vm.state.value.topics.isEmpty())
            assertTrue(vm.state.value.categories.isEmpty())
            vm.idle()
            assertEquals(1, vm.state.value.newCount)
            vm.select("bookmarks")
            vm.idle()
            vm.setUser(bob)
            assertEquals("unread", vm.state.value.filter)
            assertNull(vm.state.value.newCount)
            assertNull(vm.state.value.next)
            vm.idle()
            assertEquals(0, vm.state.value.newCount)
            assertEquals(2, vm.state.value.accountId)
            assertTrue(vm.state.value.forUser(null).topics.isEmpty())
            assertEquals("latest", vm.state.value.forUser(null).filter)
            vm.setUser(null)
            assertTrue(vm.state.value.topics.isEmpty())
            vm.idle()
            assertEquals(listOf(10), vm.state.value.topics.map(Topic::id))
            assertNull(vm.state.value.newCount)
            assertNull(vm.state.value.accountId)
        } finally { vm.viewModelScope.cancel(); server.shutdown(); Dispatchers.resetMain() }
    }

    @Test fun logoutDuringPaginationDiscardsOldPageAndAuxiliaryCountFailureKeepsFeed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val server = MockWebServer()
        val pageStarted = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("page=1") -> { pageStarted.countDown(); page(99).setBodyDelay(400, TimeUnit.MILLISECONDS) }
                request.path == "/site.json" -> MockResponse().setResponseCode(429).setBody("{}")
                request.path!!.contains("topic-tracking") -> MockResponse().setResponseCode(503).setBody("{}")
                request.path == "/latest.json" -> page(1)
                else -> page(2, "/unread.json?page=1")
            }
        }
        server.start()
        val vm = FeedViewModel(CommunityRepository(DiscourseClient(NoCookies, server.url("/"),
            cooldown = com.kudo.android.network.RequestCooldown({ testScheduler.currentTime }))), initialUser = alice)
        try {
            vm.idle()
            assertEquals(listOf(2), vm.state.value.topics.map(Topic::id))
            assertNull(vm.state.value.error)
            assertNull(vm.state.value.newCount)
            // Auxiliary 429 preserves the loaded page, but subsequent navigation waits for cooldown.
            advanceTimeBy(15_000)
            vm.more()
            runCurrent()
            assertTrue(withContext(Dispatchers.IO) { pageStarted.await(3, TimeUnit.SECONDS) })
            vm.setUser(null)
            vm.idle()
            assertEquals(listOf(1), vm.state.value.topics.map(Topic::id))
            assertNull(vm.state.value.next)
            assertFalse(vm.state.value.loadingMore)
            assertNull(vm.state.value.moreError)
        } finally { vm.viewModelScope.cancel(); server.shutdown(); Dispatchers.resetMain() }
    }

    @Test fun paginationRetriesDeduplicatesAndStopsRepeatedCursor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val server = MockWebServer()
        val failPage = AtomicBoolean(true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/site.json" -> MockResponse().setBody("""{"categories":[]}""")
                request.path!!.contains("page=1") && failPage.getAndSet(false) -> MockResponse().setResponseCode(503).setBody("{}")
                request.path!!.contains("page=1") -> MockResponse().setBody("""{"topic_list":{"topics":[{"id":1,"title":"重复"},{"id":2,"title":"新条目"}],"more_topics_url":"/latest.json?page=1"}}""")
                else -> page(1, "/latest.json?page=1")
            }
        }
        server.start()
        val vm = FeedViewModel(CommunityRepository(DiscourseClient(NoCookies, server.url("/"))))
        try {
            vm.idle(); vm.more(); vm.idle()
            assertNotNull(vm.state.value.moreError)
            assertEquals(listOf(1), vm.state.value.topics.map(Topic::id))
            vm.more(); vm.idle()
            assertEquals(listOf(1, 2), vm.state.value.topics.map(Topic::id))
            assertNull(vm.state.value.next)
            assertNull(vm.state.value.moreError)
        } finally { vm.viewModelScope.cancel(); server.shutdown(); Dispatchers.resetMain() }
    }

    @Test fun filterOrderMatchesGuestAndMemberNavigation() {
        assertEquals(listOf("latest", "hot", "top"), FeedFilter.available(false).map { it.value })
        assertEquals(listOf("unread", "latest", "new", "hot", "top", "posted", "read", "bookmarks"), FeedFilter.available(true).map { it.value })
    }

    @Test fun loginOrPublishRefreshIsConsumedOnceAcrossRootReentry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/site.json")
                MockResponse().setBody("""{"categories":[{"id":1,"name":"分类"}]}""") else page(1)
        }
        server.start()
        val vm = FeedViewModel(CommunityRepository(DiscourseClient(NoCookies, server.url("/"))))
        try {
            vm.idle()
            val initial = server.requestCount
            vm.refreshIfRequested(0); vm.idle()
            assertEquals(initial, server.requestCount)
            vm.refreshIfRequested(1); vm.idle()
            val afterLogin = server.requestCount
            assertEquals(initial + 1, afterLogin)
            // Returning from a topic recreates the root effect with the same login/publish epoch.
            vm.refreshIfRequested(1); vm.idle()
            assertEquals("返回首页不得重新消费已完成的登录刷新", afterLogin, server.requestCount)
            vm.refreshIfRequested(2); vm.idle()
            assertEquals(afterLogin + 1, server.requestCount)
        } finally { vm.viewModelScope.cancel(); server.shutdown(); Dispatchers.resetMain() }
    }
}
