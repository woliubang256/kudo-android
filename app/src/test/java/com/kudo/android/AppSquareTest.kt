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
        val vm = AppSquareViewModel { _, page ->
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

    @Test fun newSearchCancelsDebounceAndCannotBeOverwrittenByAnOlderResponse() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vm = AppSquareViewModel { query, _ ->
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
