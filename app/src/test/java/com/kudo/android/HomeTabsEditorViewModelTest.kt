package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.feed.HomeTabsEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class HomeTabsEditorViewModelTest {
    private val latest = HomeTab.filter(FeedFilter.LATEST)
    private val category = HomeTab.category(Category(7, "开发", "development", "", "", 0))
    private val tag = HomeTab.tag(TopicTag("Kotlin", "kotlin", id = 7))

    @Test fun editingKeepsInitialAndSavedListsUntouchedAndOnlySavePersistsTheDraft() = runTest {
        fixture {
            val initial = mutableListOf(latest, category)
            val original = initial.toList()
            val persisted = mutableListOf<List<HomeTab>>()
            val model = editor(initialTabs = initial, persist = { persisted += it.toList() })
            model.idle()
            assertFalse(model.state.value.dirty)

            model.move(category.key, 0)
            assertEquals(listOf(category, latest), model.state.value.tabs)
            model.remove(latest.key)
            model.add(tag)
            assertEquals(listOf(category, tag), model.state.value.tabs)
            assertEquals(original, initial)
            assertEquals(original, model.state.value.savedTabs)
            assertTrue(model.state.value.dirty)
            assertTrue(persisted.isEmpty())

            model.save()
            assertEquals(listOf(listOf(category, tag)), persisted)
            assertEquals(listOf(category, tag), model.state.value.savedTabs)
            assertFalse(model.state.value.dirty)
            model.remove(tag.key)
            assertEquals(listOf(category, tag), persisted.single())
            assertEquals(listOf(category, tag), model.state.value.savedTabs)
            assertTrue(model.state.value.dirty)
            assertEquals(original, initial)
        }
    }

    @Test fun moveClampsDestinationAndRemoveAlwaysLeavesAtLeastOneTab() = runTest {
        fixture {
            val initial = mutableListOf(latest, category, tag)
            val original = initial.toList()
            val model = editor(initialTabs = initial)
            model.idle()
            model.move(tag.key, -100)
            assertEquals(listOf(tag, latest, category), model.state.value.tabs)
            model.move(tag.key, 100)
            assertEquals(original, model.state.value.tabs)
            assertFalse(model.state.value.dirty)
            model.move("missing", 0)
            assertEquals(original, model.state.value.tabs)

            model.remove(latest.key)
            model.remove(category.key)
            model.remove(tag.key)
            model.remove("missing")
            assertEquals(listOf(tag), model.state.value.tabs)
            assertEquals(original, initial)
            assertEquals(original, model.state.value.savedTabs)
        }
    }

    @Test fun addRejectsDuplicatesByStableKeyEvenWhenCategoryOrTagMetadataChanges() = runTest {
        fixture {
            val model = editor(initialTabs = listOf(latest, category, tag))
            model.idle()
            val original = model.state.value.tabs
            model.add(category.copy(label = "新分类名"))
            model.add(tag.copy(value = "renamed", label = "新标签名"))
            model.add(latest.copy(label = "新筛选名"))
            assertEquals(original, model.state.value.tabs)
            assertFalse(model.state.value.dirty)

            val another = category.copy(value = "10", label = "生活")
            model.add(another)
            model.add(another)
            assertEquals(original + another, model.state.value.tabs)
            assertEquals(model.state.value.tabs.size, model.state.value.tabs.map(HomeTab::key).distinct().size)
            model.remove(category.key)
            model.add(category)
            assertEquals(listOf(latest, tag, another, category), model.state.value.tabs)
        }
    }

    @Test fun restoreUsesIdentityDefaultsButDoesNotPersistUntilExplicitSave() = runTest {
        for (accountId in listOf(null, 7)) {
            fixture {
                val persisted = mutableListOf<List<HomeTab>>()
                val model = editor(accountId, listOf(category), persist = { persisted += it.toList() })
                model.idle()
                model.restore()
                assertEquals(accountId, model.state.value.accountId)
                assertEquals(defaultHomeTabs(accountId != null), model.state.value.tabs)
                assertEquals(listOf(category), model.state.value.savedTabs)
                assertTrue(model.state.value.dirty)
                assertTrue(persisted.isEmpty())

                model.save()
                assertEquals(listOf(defaultHomeTabs(accountId != null)), persisted)
                assertEquals(model.state.value.tabs, model.state.value.savedTabs)
                assertFalse(model.state.value.dirty)
                model.remove("hot")
                assertEquals(defaultHomeTabs(accountId != null), persisted.single())
            }
        }
    }

    @Test fun categoryCandidatesUseParentIdToIncludeOnlyTopLevelCategoriesAndSearchLocally() = runTest {
        fixture {
            val model = editor()
            model.idle()
            assertEquals(listOf(8, 7, 9, 10), model.state.value.categories.map(Category::id))
            assertEquals(listOf(7, null, 8, null), model.state.value.categories.map(Category::parentId))
            model.source(HomeTabType.CATEGORY)
            assertEquals(listOf("category:7", "category:10"), model.state.value.candidates.map(HomeTab::key))
            model.query("开发")
            assertEquals(listOf("category:7"), model.state.value.candidates.map(HomeTab::key))
            model.query("10")
            assertEquals(listOf("category:10"), model.state.value.candidates.map(HomeTab::key))
            model.query("子分类")
            assertTrue(model.state.value.candidates.isEmpty())
            model.query("")
            assertEquals(listOf("category:7", "category:10"), model.state.value.candidates.map(HomeTab::key))
            advanceTimeBy(1000)
            model.idle()
            assertEquals(listOf("/site.json"), requests.map { it.url.encodedPath })
        }
    }

    @Test fun categoryLoadReusesRepositoryCatalogAndFailedLoadCanRetryWithoutTagRequests() = runTest {
        val categoryAttempts = AtomicInteger()
        fixture(response = { request ->
            if (request.url.encodedPath == "/site.json" && categoryAttempts.incrementAndGet() == 1) Reply("{}", 503)
            else Reply(categories)
        }) {
            val model = editor()
            model.idle()
            model.source(HomeTabType.CATEGORY)
            assertNotNull(model.state.value.categoryError)
            assertEquals(model.state.value.categoryError, model.state.value.error)
            assertFalse(model.state.value.loading)
            model.retry()
            assertTrue(model.state.value.loading)
            assertNull(model.state.value.error)
            model.idle()
            assertNull(model.state.value.categoryError)
            assertEquals(listOf("category:7", "category:10"), model.state.value.candidates.map(HomeTab::key))
            val another = editor()
            another.idle()
            assertEquals(model.state.value.categories, another.state.value.categories)
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.url.encodedPath == "/site.json" })
        }
    }

    @Test fun tagQueriesDebounceForThreeHundredMillisecondsAndCancelSupersededPendingTerms() = runTest {
        fixture {
            val model = editor()
            model.idle()
            model.source(HomeTabType.TAG)
            model.query("  ")
            advanceTimeBy(1000)
            runCurrent()
            assertTrue(tagRequests().isEmpty())
            assertFalse(model.state.value.tagsLoading)

            model.query("ko")
            runCurrent()
            assertTrue(model.state.value.tagsLoading)
            advanceTimeBy(299)
            runCurrent()
            assertTrue(tagRequests().isEmpty())
            model.query("  kotlin  ")
            runCurrent()
            advanceTimeBy(299)
            runCurrent()
            assertTrue(tagRequests().isEmpty())
            advanceTimeBy(1)
            runCurrent()
            model.idle()
            assertEquals(listOf("kotlin"), tagRequests().map { it.url.queryParameter("q") })
            assertEquals(setOf("q"), tagRequests().single().url.queryParameterNames)
            assertEquals(listOf(HomeTab.tag(TopicTag("服务端标签", "server-tag", id = 7))), model.state.value.candidates)
            assertFalse(model.state.value.tagsLoading)
            assertNull(model.state.value.tagError)
        }
    }

    @Test fun newestTagQueryWinsWhenCancelledOldSuccessOrFailureArrivesLast() = runTest {
        for (oldCode in listOf(200, 503)) {
            val stale = PendingReply(Reply(tags("旧标签", "old", 1), oldCode))
            fixture(response = { request ->
                when {
                    request.url.encodedPath == "/site.json" -> Reply(categories)
                    request.url.queryParameter("q") == "old" -> stale.awaitRelease()
                    else -> Reply(tags("新标签", "new", 2))
                }
            }) {
                try {
                    val model = editor()
                    model.idle()
                    model.source(HomeTabType.TAG)
                    model.query("old")
                    runCurrent()
                    advanceTimeBy(300)
                    runCurrent()
                    stale.awaitEntered()
                    val oldCall = calls.single { it.request().url.queryParameter("q") == "old" }

                    model.query("new")
                    runCurrent()
                    assertTrue("新查询必须取消正在进行的旧 HTTP 请求", oldCall.isCanceled())
                    assertTrue(model.state.value.tags.isEmpty())
                    assertTrue(model.state.value.tagsLoading)
                    advanceTimeBy(300)
                    runCurrent()
                    model.idle()
                    assertEquals(listOf(TopicTag("新标签", "new", id = 2)), model.state.value.tags)
                    assertNull(model.state.value.tagError)

                    stale.release()
                    awaitNetworkIdle()
                    runCurrent()
                    assertEquals(listOf(TopicTag("新标签", "new", id = 2)), model.state.value.tags)
                    assertEquals(listOf("old", "new"), tagRequests().map { it.url.queryParameter("q") })
                    assertNull(model.state.value.tagError)
                    assertFalse(model.state.value.tagsLoading)
                } finally {
                    stale.release()
                }
            }
        }
    }

    @Test fun clearingTagQueryCancelsInFlightRequestAndRejectsItsLateResults() = runTest {
        val stale = PendingReply(Reply(tags("旧标签", "old", 1)))
        fixture(response = { request ->
            if (request.url.encodedPath == "/site.json") Reply(categories) else stale.awaitRelease()
        }) {
            try {
                val model = editor()
                model.idle()
                model.source(HomeTabType.TAG)
                model.query("old")
                runCurrent()
                advanceTimeBy(300)
                runCurrent()
                stale.awaitEntered()
                model.query("  ")
                runCurrent()
                assertTrue(calls.single { it.request().url.encodedPath == "/tags/filter/search" }.isCanceled())
                assertTrue(model.state.value.tags.isEmpty())
                assertFalse(model.state.value.tagsLoading)
                assertNull(model.state.value.tagError)
                stale.release()
                awaitNetworkIdle()
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, tagRequests().size)
                assertTrue(model.state.value.candidates.isEmpty())
                assertNull(model.state.value.tagError)
            } finally {
                stale.release()
            }
        }
    }

    @Test fun failedTagQueryCanRetryTheSameTrimmedTermAndClearsOnlyItsError() = runTest {
        val attempts = AtomicInteger()
        fixture(response = { request ->
            when {
                request.url.encodedPath == "/site.json" -> Reply(categories)
                attempts.incrementAndGet() == 1 -> Reply("{}", 503)
                else -> Reply(tags("服务端标签", "server-tag", 7))
            }
        }) {
            val model = editor()
            model.idle()
            model.source(HomeTabType.TAG)
            model.query(" kotlin ")
            runCurrent()
            advanceTimeBy(300)
            runCurrent()
            model.idle()
            assertEquals(FailureKind.SERVER, (model.state.value.tagError as? ApiFailure)?.kind)
            assertEquals(model.state.value.tagError, model.state.value.error)
            assertFalse(model.state.value.tagsLoading)
            assertTrue(model.state.value.tags.isEmpty())
            val loadedCategories = model.state.value.categories

            model.retry()
            assertNull(model.state.value.tagError)
            assertTrue(model.state.value.tagsLoading)
            runCurrent()
            advanceTimeBy(299)
            runCurrent()
            assertEquals(1, tagRequests().size)
            advanceTimeBy(1)
            runCurrent()
            model.idle()
            assertEquals(listOf("kotlin", "kotlin"), tagRequests().map { it.url.queryParameter("q") })
            assertEquals(listOf(TopicTag("服务端标签", "server-tag", id = 7)), model.state.value.tags)
            assertNull(model.state.value.error)
            assertFalse(model.state.value.tagsLoading)
            assertEquals(loadedCategories, model.state.value.categories)
            assertEquals(1, requests.count { it.url.encodedPath == "/site.json" })
        }
    }

    @Test fun switchingSourcesCancelsTagWorkAndLocalSourcesDoNotSendUnrelatedRequests() = runTest {
        val stale = PendingReply(Reply(tags("旧标签", "old", 1)))
        fixture(response = { request ->
            if (request.url.encodedPath == "/site.json") Reply(categories) else stale.awaitRelease()
        }) {
            try {
                val model = editor()
                model.idle()
                model.query("开发")
                model.retry()
                model.source(HomeTabType.CATEGORY)
                model.query("7")
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, requests.size)

                model.source(HomeTabType.TAG)
                runCurrent()
                advanceTimeBy(100)
                model.source(HomeTabType.FILTER)
                runCurrent()
                advanceTimeBy(1000)
                runCurrent()
                assertTrue(tagRequests().isEmpty())
                model.query("old")
                model.source(HomeTabType.TAG)
                runCurrent()
                advanceTimeBy(300)
                runCurrent()
                stale.awaitEntered()

                model.source(HomeTabType.CATEGORY)
                model.query("生活")
                runCurrent()
                assertTrue(calls.single { it.request().url.encodedPath == "/tags/filter/search" }.isCanceled())
                assertFalse(model.state.value.tagsLoading)
                assertTrue(model.state.value.tags.isEmpty())
                assertEquals(listOf("category:10"), model.state.value.candidates.map(HomeTab::key))
                model.source(HomeTabType.DIRECTORY)
                assertEquals(HomeTabType.CATEGORY, model.state.value.source)
                stale.release()
                awaitNetworkIdle()
                advanceTimeBy(1000)
                runCurrent()
                assertEquals(1, tagRequests().size)
                assertEquals(1, requests.count { it.url.encodedPath == "/site.json" })
                assertTrue(model.state.value.tags.isEmpty())
                assertNull(model.state.value.error)
            } finally {
                stale.release()
            }
        }
    }

    private data class Reply(val body: String, val code: Int = 200)

    private class PendingReply(private val reply: Reply) {
        private val entered = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)

        fun awaitRelease(): Reply {
            entered.complete(Unit)
            if (!released.await(5, TimeUnit.SECONDS)) throw IOException("测试未及时释放旧标签响应")
            return reply
        }

        suspend fun awaitEntered() = withContext(Dispatchers.Default) {
            withTimeout(5000) { entered.await() }
        }

        fun release() { released.countDown() }
    }

    private class Fixture(response: (Request) -> Reply) {
        val requests = CopyOnWriteArrayList<Request>()
        val calls = CopyOnWriteArrayList<Call>()
        val models = mutableListOf<HomeTabsEditorViewModel>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            calls += chain.call()
            requests += request
            val reply = response(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.code).message("fixture")
                .body(reply.body.toResponseBody()).build()
        }.build()
        val repository = CommunityRepository(DiscourseClient(NoCookies, http = http))

        fun editor(accountId: Int? = null, initialTabs: List<HomeTab> = defaultHomeTabs(accountId != null),
            persist: (List<HomeTab>) -> Unit = {}) =
            HomeTabsEditorViewModel(repository, accountId, initialTabs, persist).also(models::add)

        fun tagRequests() = requests.filter { it.url.encodedPath == "/tags/filter/search" }

        suspend fun awaitNetworkIdle() = withContext(Dispatchers.Default) {
            withTimeout(5000) {
                while (http.dispatcher.runningCallsCount() > 0 || http.dispatcher.queuedCallsCount() > 0) delay(1)
            }
        }
    }

    private suspend fun HomeTabsEditorViewModel.idle() = withContext(Dispatchers.Default) {
        withTimeout(5000) { viewModelScope.coroutineContext.job.children.toList().joinAll() }
    }

    private suspend fun TestScope.fixture(
        response: (Request) -> Reply = { request ->
            if (request.url.encodedPath == "/site.json") Reply(categories) else Reply(tags("服务端标签", "server-tag", 7))
        },
        block: suspend Fixture.() -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(response)
        try {
            fixture.block()
        } finally {
            try {
                fixture.models.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
                fixture.http.dispatcher.executorService.shutdown()
                withContext(Dispatchers.IO) {
                    assertTrue(fixture.http.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
                }
                fixture.http.connectionPool.evictAll()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    companion object {
        private val categories = """{"categories":[
            {"id":8,"name":"子分类","parent_category_id":7},
            {"id":7,"name":"开发","parent_category_id":null},
            {"id":9,"name":"孙分类","parent_category_id":8},
            {"id":10,"name":"生活"}
        ]}"""

        private fun tags(name: String, slug: String, id: Int) =
            """{"results":[{"id":$id,"name":"$name","slug":"$slug"}]}"""
    }
}
