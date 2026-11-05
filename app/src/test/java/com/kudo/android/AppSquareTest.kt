package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.apps.AppSquareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class AppSquareTest {
    private val first = MiniAppEntry("01a1966a-f4a0-7b43-ad9c-c46b7f0e0281", "2048", null)
    private val second = first.copy(id = "01a1966e-935a-79e0-8c76-9b0f7552c578", name = "打砖块")
    private fun body(page: Int = 0) = """{"apps":[{"app_id":"${first.id}","name":"2048","icon_url":"javascript:bad"}],"page":$page,"per_page":1,"total":2}"""
    private suspend fun AppSquareViewModel.finished() = withContext(Dispatchers.IO) {
        withTimeout(5000) { state.first { !it.loading } }
    }

    @Test fun directoryUsesOfficialQueryAndZeroBasedPageAndFiltersUnsafeIcons() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(MockResponse().setBody(body(1)))
            val result = CommunityRepository(DiscourseClient(NoCookies, server.url("/"))).miniApps(" 中文 & 2048 ", 1)
            val request = server.takeRequest().requestUrl!!
            assertEquals("/mini-app-api/apps.json", request.encodedPath)
            assertEquals("中文 & 2048", request.queryParameter("q"))
            assertEquals("latest", request.queryParameter("sort"))
            assertEquals("1", request.queryParameter("page"))
            assertEquals(listOf(first), result.apps)
            assertFalse(result.hasMore)
            assertTrue(parseMiniAppDirectory(JSONObject(body()), 0).hasMore)
            assertThrows(IllegalArgumentException::class.java) { parseMiniAppDirectory(JSONObject(body()), 1) }
            val invalid = JSONObject(body()).apply { getJSONArray("apps").getJSONObject(0).put("app_id", "../bad") }
            assertTrue(parseMiniAppDirectory(invalid, 0).apps.isEmpty())
        } finally { server.shutdown() }
    }

    @Test fun paginationRetriesTheSamePageAndDeduplicatesWithoutLosingResults() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val calls = mutableListOf<Int>()
        var fail = true
        val vm = AppSquareViewModel { _, page, _ ->
            synchronized(calls) { calls.add(page) }
            if (page == 1 && fail) throw IllegalStateException("离线")
            MiniAppDirectoryPage(if (page == 0) listOf(first) else listOf(first, second), page == 0)
        }
        try {
            vm.finished(); vm.more()
            assertNotNull(vm.finished().moreError)
            assertEquals(listOf(first), vm.state.value.apps)
            fail = false; vm.more()
            assertEquals(listOf(first, second), vm.finished().apps)
            vm.more()
            assertEquals(listOf(0, 1, 1), calls)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun directoryKeepsFractionalRatingsAndLongUsageCountsWithoutExtraRequests() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val json = JSONObject(body()).apply { getJSONArray("apps").getJSONObject(0)
                .put("rating_average", 4.35).put("rating_count", 3_000_000_000L)
                .put("usage_count", 4_000_000_000L).put("recent_usage_count", 10) }
            server.enqueue(MockResponse().setBody(json.toString()))
            val app = CommunityRepository(DiscourseClient(NoCookies, server.url("/"))).miniApps().apps.single()
            assertEquals(4.35, app.ratingAverage!!, .001)
            assertEquals(3_000_000_000L, app.ratingCount!!)
            assertEquals(4_000_000_000L, app.usageCount!!)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun absentAndInvalidDirectoryStatisticsStayMissingButRealZeroIsPreserved() {
        val absent = parseMiniAppDirectory(JSONObject(body()), 0).apps.single()
        assertNull(absent.ratingAverage); assertNull(absent.ratingCount); assertNull(absent.usageCount)
        for ((rating, count) in listOf(-1 to -1, 6 to 1.5, "4" to "10", true to true, JSONObject.NULL to JSONObject.NULL)) {
            val json = JSONObject(body()).apply { getJSONArray("apps").getJSONObject(0)
                .put("rating_average", rating).put("rating_count", count).put("usage_count", count) }
            val app = parseMiniAppDirectory(json, 0).apps.single()
            assertNull(app.ratingAverage); assertNull(app.ratingCount); assertNull(app.usageCount)
        }
        val zero = JSONObject(body()).apply { getJSONArray("apps").getJSONObject(0)
            .put("rating_average", 0).put("rating_count", 0).put("usage_count", 0) }
        val app = parseMiniAppDirectory(zero, 0).apps.single()
        assertEquals(0.0, app.ratingAverage!!, 0.0)
        assertEquals(0L, app.ratingCount!!); assertEquals(0L, app.usageCount!!)
    }

    @Test fun everySortUsesTheOfficialValueAndDetailsOnlyRequestTheInfoEndpoint() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            for (sort in MiniAppSort.entries) {
                server.enqueue(MockResponse().setBody(body()))
                repository.miniApps(" 游戏 & test ", sort = sort)
                val url = server.takeRequest().requestUrl!!
                assertEquals(sort.apiValue, url.queryParameter("sort"))
                assertEquals("游戏 & test", url.queryParameter("q"))
                assertEquals("0", url.queryParameter("page"))
            }
            server.enqueue(MockResponse().setBody("""{"app":{"app_id":"${first.id}","name":"2048","rating_average":4.35,"rating_count":7}}"""))
            val details = repository.miniAppDetails(first.id)
            assertEquals("/mini-app-api/public/${first.id}/info", server.takeRequest().path)
            assertEquals(4.35, details.ratingAverage!!, .001)
            assertEquals(7L, details.ratingCount!!)
            assertEquals(5, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"app":{"app_id":"${second.id}","name":"错误应用"}}"""))
            assertTrue(runCatching { repository.miniAppDetails(first.id) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repository.miniAppDetails("../bad") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(6, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun sortingKeepsTheSearchRestartsPaginationAndIgnoresSlowOldPages() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val oldPageStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<Triple<String, Int, MiniAppSort>>())
        val vm = AppSquareViewModel { query, page, sort ->
            calls.add(Triple(query, page, sort))
            if (page == 1 && sort == MiniAppSort.LATEST) withContext(NonCancellable) {
                oldPageStarted.complete(Unit); release.await()
            }
            MiniAppDirectoryPage(listOf(if (sort == MiniAppSort.LATEST) first else second), page == 0)
        }
        try {
            vm.finished()
            vm.search(" 游戏 "); advanceTimeBy(300); runCurrent(); vm.finished()
            vm.more()
            withContext(Dispatchers.IO) { withTimeout(5000) { oldPageStarted.await() } }
            vm.sort(MiniAppSort.RATING)
            assertEquals(listOf(second), vm.finished().apps)
            assertEquals(" 游戏 ", vm.state.value.query)
            assertEquals(0, vm.state.value.page)
            vm.sort(MiniAppSort.RATING)
            vm.more(); vm.finished()
            assertEquals(listOf(Triple("游戏", 0, MiniAppSort.RATING), Triple("游戏", 1, MiniAppSort.RATING)), calls.takeLast(2))
            release.complete(Unit)
            vm.viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertEquals(listOf(second), vm.state.value.apps)
            assertEquals(MiniAppSort.RATING, vm.state.value.sort)
        } finally { release.complete(Unit); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun newSearchCancelsDebounceAndCannotBeOverwrittenByAnOlderResponse() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vm = AppSquareViewModel { query, _, _ ->
            if (query.isEmpty()) withContext(NonCancellable) { started.complete(Unit); release.await() }
            MiniAppDirectoryPage(listOf(if (query.isEmpty()) first else second), false)
        }
        try {
            withContext(Dispatchers.IO) { withTimeout(5000) { started.await() } }
            vm.search("2"); vm.search("砖块")
            advanceTimeBy(300); runCurrent()
            assertEquals(listOf(second), vm.finished().apps)
            release.complete(Unit)
            assertEquals("砖块", vm.state.value.query)
            assertEquals(listOf(second), vm.state.value.apps)
            vm.search(""); advanceTimeBy(300); runCurrent()
            assertEquals(listOf(first), vm.finished().apps)
        } finally { release.complete(Unit); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }
}
