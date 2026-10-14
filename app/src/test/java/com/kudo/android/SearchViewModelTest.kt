package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.feed.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val result = """{"topics":[{"id":3,"title":"主题"}],"posts":[{"id":31,"topic_id":3,"post_number":2}],"grouped_search_result":{"more_full_page_results":true}}"""
    private suspend fun SearchViewModel.finished() = withContext(Dispatchers.IO) { withTimeout(5000) { state.first { it.searched && !it.loading } } }
    private suspend fun SearchViewModel.historyFinished() = withContext(Dispatchers.IO) { withTimeout(5000) { state.first { !it.historyLoading } } }

    @Test fun guestSearchDoesNotReadHistoryAndSortIsSentToServer() = runTest {
        fixture { repository, requests, models ->
            val vm = SearchViewModel(repository).also(models::add)
            vm.changeSort(SearchSort.LIKES); vm.search("安卓")
            assertEquals(2, vm.finished().hits.posts.single().postNumber)
            assertEquals("安卓 order:likes", requests.single().requestUrl!!.queryParameter("q"))
            assertTrue(requests.none { it.path!!.contains("recent-searches") })
            vm.changeSort(SearchSort.READ)
            assertEquals(SearchSort.LIKES, vm.state.value.sort)
        }
    }

    @Test fun historyFailureDoesNotBlockSearchOrReplaceResults() = runTest {
        fixture(response = { if (it.requestUrl!!.encodedPath.contains("recent-searches")) MockResponse().setResponseCode(503).setBody("{}") else MockResponse().setBody(result) }) { repository, _, models ->
            val vm = SearchViewModel(repository, 7).also(models::add)
            assertNotNull(vm.historyFinished().historyError)
            vm.search("安卓")
            assertNull(vm.finished().error)
            assertEquals(31, vm.state.value.hits.posts.single().postId)
        }
    }

    @Test fun failedNextPageKeepsResultsAndRetriesSamePage() = runTest {
        val fail = AtomicBoolean(true)
        fixture(response = { request ->
            if (request.requestUrl!!.queryParameter("page") == "2" && fail.getAndSet(false)) MockResponse().setResponseCode(503).setBody("{}")
            else MockResponse().setBody(if (request.requestUrl!!.queryParameter("page") == "2") result.replace("31", "32") else result)
        }) { repository, requests, models ->
            val vm = SearchViewModel(repository).also(models::add)
            vm.search("安卓"); vm.finished(); vm.search("安卓", true)
            val failed = vm.finished()
            assertEquals(1, failed.page); assertEquals(listOf(31), failed.hits.posts.map { it.postId }); assertNotNull(failed.moreError)
            vm.search("ignored-new-query", true)
            val retried = vm.finished()
            assertEquals(2, retried.page); assertEquals(listOf(31, 32), retried.hits.posts.map { it.postId }); assertNull(retried.moreError)
            assertEquals(listOf("1", "2", "2"), requests.map { it.requestUrl!!.queryParameter("page") })
            assertTrue(requests.all { it.requestUrl!!.queryParameter("q") == "安卓 order:relevance" })
        }
    }

    @Test fun usersStripPostFiltersAndCannotLoadFakeNextPages() = runTest {
        fixture { repository, requests, models ->
            val vm = SearchViewModel(repository).also(models::add)
            vm.search("neo category:4 order:likes"); vm.finished(); vm.changeKind(SearchKind.USERS)
            assertEquals(SearchKind.USERS, vm.finished().kind)
            assertEquals("neo", requests.last().requestUrl!!.queryParameter("term"))
            val count = requests.size
            vm.search("neo", true)
            assertEquals(count, requests.size)
        }
    }

    @Test fun newSearchWinsEvenWhenOldResponseArrivesLater() = runTest {
        val oldEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture(response = { request ->
            if (request.requestUrl!!.queryParameter("q")!!.startsWith("old")) { oldEntered.countDown(); release.await(5, TimeUnit.SECONDS) }
            MockResponse().setBody(result.replace("主题", request.requestUrl!!.queryParameter("q")!!))
        }) { repository, _, models ->
            val vm = SearchViewModel(repository).also(models::add)
            try {
                vm.search("old"); assertTrue(oldEntered.await(3, TimeUnit.SECONDS))
                vm.search("new"); assertEquals("new", vm.finished().query)
            } finally { release.countDown() }
            assertEquals("new order:relevance", vm.state.value.hits.posts.single().topic.title)
        }
    }

    @Test fun logoutHidesAndCancelsInFlightHistoryBeforeItReturns() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture(response = {
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            MockResponse().setBody("""{"recent_searches":["private-history"]}""")
        }) { repository, _, models ->
            val vm = SearchViewModel(repository, 7).also(models::add)
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertTrue(vm.state.value.forAccount(null).recent.isEmpty())
                vm.useAccount(null)
                assertTrue(vm.state.value.recent.isEmpty()); assertFalse(vm.state.value.historyLoading)
            } finally { release.countDown() }
            assertNull(vm.state.value.accountId)
            assertTrue(vm.state.value.recent.isEmpty())
        }
    }

    private suspend fun TestScope.fixture(
        response: (RecordedRequest) -> MockResponse = { request ->
            MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                "/u/recent-searches.json" -> """{"recent_searches":["server-history"]}"""
                "/u/search/users.json" -> """{"users":[{"id":7,"username":"neo"}]}"""
                else -> result
            })
        }, block: suspend (CommunityRepository, List<RecordedRequest>, MutableList<SearchViewModel>) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val models = mutableListOf<SearchViewModel>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return response(request) }
            }
            server.start()
            try { block(CommunityRepository(DiscourseClient(NoCookies, server.url("/"))), requests, models) }
            finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; Dispatchers.resetMain() }
        }
    }
}
