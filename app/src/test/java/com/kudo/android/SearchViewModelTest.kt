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
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val result = """{"topics":[{"id":3,"title":"主题"}],"posts":[{"id":31,"topic_id":3,"post_number":2}],"grouped_search_result":{"more_full_page_results":true}}"""
    private suspend fun SearchViewModel.finished() = withContext(Dispatchers.IO) { withTimeout(5000) { state.first { it.searched && !it.loading } } }
    private suspend fun SearchViewModel.historyFinished() = withContext(Dispatchers.IO) { withTimeout(5000) { state.first { !it.historyLoading } } }
    private suspend fun SearchViewModel.clearHistoryFinished() = withContext(Dispatchers.IO) { withTimeout(5000) { state.first { !it.historyClearing } } }

    @Test fun switchingKindsKeepsResultsAndPositionsWithoutRepeatingSuccessfulRequests() = runTest {
        fixture { repository, requests, models ->
            val vm = SearchViewModel(repository).also(models::add)
            vm.search("neo"); val posts = vm.finished().hits
            vm.saveScrollPosition(12, 37)
            vm.changeKind(SearchKind.USERS); vm.finished()
            vm.saveScrollPosition(3, 21)
            val count = requests.size
            vm.changeKind(SearchKind.POSTS)
            assertFalse(vm.state.value.loading)
            assertEquals(posts, vm.state.value.hits)
            assertEquals(12, vm.scrollIndex); assertEquals(37, vm.scrollOffset)
            vm.changeKind(SearchKind.USERS)
            assertEquals(3, vm.scrollIndex); assertEquals(21, vm.scrollOffset)
            assertEquals(count, requests.size)
            vm.search("new"); vm.finished()
            vm.changeKind(SearchKind.POSTS); vm.finished()
            assertEquals(0, vm.scrollIndex)
            assertEquals("new order:relevance", requests.last().requestUrl!!.queryParameter("q"))
        }
    }

    @Test fun leavingDuringLoadMoreKeepsAllPreviouslyCompletedPages() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture(response = { request ->
            MockResponse().setBody(when {
                request.requestUrl!!.encodedPath == "/u/search/users.json" -> """{"users":[{"id":7,"username":"neo"}]}"""
                request.requestUrl!!.queryParameter("page") == "2" -> result.replace("31", "32")
                request.requestUrl!!.queryParameter("page") == "3" -> {
                    entered.countDown(); release.await(5, TimeUnit.SECONDS); result.replace("31", "33")
                }
                else -> result
            })
        }) { repository, _, models ->
            val vm = SearchViewModel(repository).also(models::add)
            vm.search("neo"); vm.finished()
            vm.changeKind(SearchKind.USERS); vm.finished()
            vm.changeKind(SearchKind.POSTS)
            vm.search("neo", true); vm.finished()
            try {
                vm.search("neo", true)
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                vm.changeKind(SearchKind.USERS); vm.changeKind(SearchKind.POSTS)
                assertEquals(listOf(31, 32), vm.state.value.hits.posts.map { it.postId })
                assertEquals(2, vm.state.value.page)
                assertFalse(vm.state.value.loading)
            } finally { release.countDown() }
        }
    }

    @Test fun clearingHistoryWaitsForServerAndBlocksDuplicateDeletesAndRefreshes() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture(response = { request ->
            when {
                request.method == "DELETE" -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody("""{"success":true}""") }
                request.path == "/session/csrf" -> MockResponse().setBody("""{"csrf":"test-token"}""")
                else -> MockResponse().setBody("""{"recent_searches":["server-history"]}""")
            }
        }) { repository, requests, models ->
            val vm = SearchViewModel(repository, 7).also(models::add)
            assertEquals(listOf("server-history"), vm.historyFinished().recent)
            try {
                vm.clearHistory(); assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertEquals(listOf("server-history"), vm.state.value.recent)
                vm.clearHistory(); vm.refreshHistory()
                assertTrue(vm.state.value.historyClearing)
            } finally { release.countDown() }
            val state = vm.clearHistoryFinished()
            assertTrue(state.recent.isEmpty()); assertNull(state.historyClearError)
            assertEquals(1, requests.count { it.method == "DELETE" })
            assertEquals(1, requests.count { it.method == "GET" && it.path == "/u/recent-searches.json" })
        }
    }

    @Test fun failedClearKeepsHistoryAndExplicitRetryCanClearIt() = runTest {
        val fail = AtomicBoolean(true)
        fixture(response = { request ->
            when {
                request.method == "DELETE" && fail.getAndSet(false) -> MockResponse().setResponseCode(503).setBody("{}")
                request.method == "DELETE" -> MockResponse().setBody("""{"success":true}""")
                request.path == "/session/csrf" -> MockResponse().setBody("""{"csrf":"test-token"}""")
                else -> MockResponse().setBody("""{"recent_searches":["server-history"]}""")
            }
        }) { repository, requests, models ->
            val vm = SearchViewModel(repository, 7).also(models::add)
            vm.historyFinished(); vm.clearHistory()
            val failed = vm.clearHistoryFinished()
            assertEquals(listOf("server-history"), failed.recent)
            assertNotNull(failed.historyClearError)
            assertNull(failed.historyError)
            assertEquals(1, requests.count { it.method == "DELETE" })
            vm.clearHistory()
            assertTrue(vm.clearHistoryFinished().recent.isEmpty())
            assertNull(vm.state.value.historyClearError)
            assertEquals(2, requests.count { it.method == "DELETE" })
        }
    }

    @Test fun cancelledHistoryReadCannotRestoreEntriesAfterClearing() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockRead = AtomicBoolean(false)
        fixture(response = { request ->
            when {
                request.path == "/session/csrf" -> MockResponse().setBody("""{"csrf":"test-token"}""")
                request.method == "DELETE" -> MockResponse().setBody("""{"success":true}""")
                else -> {
                    if (blockRead.get()) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                    MockResponse().setBody("""{"recent_searches":["server-history"]}""")
                }
            }
        }) { repository, _, models ->
            val vm = SearchViewModel(repository, 7).also(models::add)
            vm.historyFinished(); blockRead.set(true); vm.refreshHistory()
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                vm.clearHistory()
                assertTrue(vm.clearHistoryFinished().recent.isEmpty())
            } finally { release.countDown() }
            assertTrue(vm.state.value.recent.isEmpty())
        }
    }

    @Test fun switchingAccountsDuringDeletionKeepsTheNewAccountsHistoryAndGuestCannotDelete() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val newAccount = AtomicBoolean(false)
        fixture(response = { request ->
            when {
                request.path == "/session/csrf" -> MockResponse().setBody("""{"csrf":"test-token"}""")
                request.method == "DELETE" -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); MockResponse().setBody("""{"success":true}""") }
                else -> MockResponse().setBody(if (newAccount.get()) """{"recent_searches":["new-account-history"]}""" else """{"recent_searches":["server-history"]}""")
            }
        }) { repository, requests, models ->
            val vm = SearchViewModel(repository, 7).also(models::add)
            vm.historyFinished()
            try {
                vm.clearHistory(); assertTrue(entered.await(3, TimeUnit.SECONDS))
                newAccount.set(true); vm.useAccount(8)
                assertEquals(listOf("new-account-history"), vm.historyFinished().recent)
                assertFalse(vm.state.value.historyClearing)
            } finally { release.countDown() }
            assertEquals(8, vm.state.value.accountId)
            assertEquals(listOf("new-account-history"), vm.state.value.recent)
            vm.useAccount(null); vm.clearHistory()
            assertEquals(1, requests.count { it.method == "DELETE" })
        }
    }

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
        fixture(response = { request ->
            val query = request.requestUrl!!.queryParameter("q")!!
            if (query.startsWith("old")) {
                oldEntered.countDown()
                MockResponse().setBody(result.replace("主题", query)).setBodyDelay(500, TimeUnit.MILLISECONDS)
            } else MockResponse().setBody(result.replace("主题", query))
        }) { repository, _, models ->
            val vm = SearchViewModel(repository).also(models::add)
            vm.search("old"); assertTrue(oldEntered.await(3, TimeUnit.SECONDS))
            vm.search("new")
            val fresh = withContext(Dispatchers.IO) {
                withTimeout(5000) {
                    vm.state.first { it.searched && !it.loading && it.query == "new" }
                }
            }
            assertNull("New search must complete without a network error", fresh.error)
            assertEquals("new order:relevance", fresh.hits.posts.single().topic.title)
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
            val listenAddress = InetAddress.getLoopbackAddress()
            server.start(listenAddress, 0)
            val endpoint = server.url("/").newBuilder().host(requireNotNull(listenAddress.hostAddress)).build()
            try { block(CommunityRepository(DiscourseClient(NoCookies, endpoint)), requests, models) }
            finally { models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }; Dispatchers.resetMain() }
        }
    }
}
