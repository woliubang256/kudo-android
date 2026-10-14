package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.topic.PostHistoryViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PostHistoryViewModelTest {
    private val cookies = object : SessionCookies { override fun get(url: String) = ""; override fun set(url: String, cookie: String) {} }
    private val source = """{"id":81,"post_number":8,"user_id":2,"username":"author","version":7,"can_view_edit_history":true,"created_at":"2026-10-15T01:00:00Z"}"""
    private fun revision(number: Int, previous: Int?): String = JSONObject().put("post_id", 81).put("current_revision", number)
        .put("previous_revision", previous ?: JSONObject.NULL).put("username", "editor")
        .put("body_changes", JSONObject().put("side_by_side", "<div class='revision-content --previous'><p>旧正文</p></div><div class='revision-content --current'><p>版本 $number</p></div>")).toString()
    private suspend fun await(predicate: () -> Boolean) = withContext(Dispatchers.IO) { withTimeout(5000) { while (!predicate()) delay(10) } }
    @Test fun followsNoncontiguousPointersAndKeepsCardsOnRetryWithoutDuplicateLoads() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            val older = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/posts/81.json" -> MockResponse().setBody(source)
                    "/posts/81/revisions/latest.json" -> MockResponse().setBody(revision(7, 4))
                    "/posts/81/revisions/4.json" -> if (older.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("{}") else MockResponse().setBody(revision(4, 2))
                    "/posts/81/revisions/2.json" -> MockResponse().setBody(revision(2, null))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val vm = PostHistoryViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), 81)
            try {
                await { !vm.state.value.loading }; assertEquals(listOf(7), vm.state.value.versions.map { it.number })
                vm.more(); vm.more(); await { !vm.state.value.loadingMore }
                assertNotNull(vm.state.value.moreError); assertEquals(1, older.get()); assertEquals(4, vm.state.value.previous)
                assertEquals(listOf(7), vm.state.value.versions.map { it.number })
                vm.more(); await { !vm.state.value.loadingMore }; vm.more(); await { !vm.state.value.loadingMore }
                assertEquals(listOf(7, 4, 2, 1), vm.state.value.versions.map { it.number })
                assertTrue(vm.state.value.versions.last().original); assertNull(vm.state.value.previous)
                val requests = List(server.requestCount) { server.takeRequest() }
                assertTrue(requests.all { it.method == "GET" }); assertFalse(requests.any { it.path?.contains("/6.json") == true })
            } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }
    @Test fun permissionFailureStopsBeforeReadingAnyRevision() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(JSONObject(source).put("can_view_edit_history", false).toString()))
            val vm = PostHistoryViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), 81)
            try {
                await { !vm.state.value.loading }; assertTrue(vm.state.value.error is ApiFailure)
                assertEquals(1, server.requestCount); assertTrue(vm.state.value.versions.isEmpty())
            } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }
    @Test fun malformedPointerCannotCauseAnEndlessLoad() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(source)); server.enqueue(MockResponse().setBody(revision(7, 7)))
            val vm = PostHistoryViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), 81)
            try {
                await { !vm.state.value.loading }; assertNotNull(vm.state.value.error); vm.more(); assertEquals(2, server.requestCount)
            } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }
}
