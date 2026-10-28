package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.feed.FeedViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class HomeTabsFeedTest {
    private val alice = Member(1, "alice", "甲", "")
    private val bob = Member(2, "bob", "乙", "")
    private val latest = HomeTab.filter(FeedFilter.LATEST)
    private val hot = HomeTab.filter(FeedFilter.HOT)
    private val category = HomeTab.category(Category(7, "开发", "development", "", "", 0))
    private val tag = HomeTab.tag(TopicTag("Kotlin", "kotlin", id = 7))

    @Test fun customCategoryAndTagUseLatestEndpointsAndKeepIndependentStableKeyPagination() = runTest {
        fixture {
            val model = feed(listOf(category, tag, latest), alice)
            model.idle()
            assertEquals("category:7", model.state.value.filter)
            assertEquals(listOf(71), model.state.value.topics.map(Topic::id))
            assertEquals(listOf("/c/7/l/latest.json", "/site.json"), paths())
            model.more()
            model.idle()
            assertEquals(listOf(71, 72), model.state.value.topics.map(Topic::id))
            assertEquals("/c/7/l/latest.json?page=2", model.state.value.next)

            model.select(tag.key)
            model.idle()
            assertEquals("tag:7", model.state.value.filter)
            assertEquals(listOf(171), model.state.value.topics.map(Topic::id))
            assertEquals("/tag/kotlin/7.json?page=1", model.state.value.next)
            model.more()
            model.idle()
            assertEquals(listOf(171, 172), model.state.value.topics.map(Topic::id))
            assertEquals("/tag/kotlin/7.json?page=2", model.state.value.next)

            model.select(latest.key)
            model.idle()
            val requestsBeforeReturn = requests.size
            model.select(category.key)
            model.idle()
            assertEquals(requestsBeforeReturn, requests.size)
            assertEquals(listOf(71, 72), model.state.value.topics.map(Topic::id))
            assertEquals("/c/7/l/latest.json?page=2", model.state.value.next)
            assertEquals(listOf(171, 172), model.pages.value.getValue(tag.key).topics.map(Topic::id))
            assertEquals("/tag/kotlin/7.json?page=2", model.pages.value.getValue(tag.key).next)
            model.more()
            model.idle()
            assertEquals(listOf(71, 72, 73), model.state.value.topics.map(Topic::id))
            assertNull(model.state.value.next)

            val requestsBeforeTagReturn = requests.size
            model.select(tag.key)
            model.idle()
            assertEquals(requestsBeforeTagReturn, requests.size)
            assertEquals(listOf(171, 172), model.state.value.topics.map(Topic::id))
            model.more()
            model.idle()
            assertEquals(listOf(171, 172, 173), model.state.value.topics.map(Topic::id))
            assertNull(model.state.value.next)
            assertEquals(listOf(
                "/c/7/l/latest.json", "/c/7/l/latest.json?page=1", "/tag/kotlin/7.json",
                "/tag/kotlin/7.json?page=1", "/c/7/l/latest.json?page=2", "/tag/kotlin/7.json?page=2",
            ), paths().filter { it.startsWith("/c/") || it.startsWith("/tag/") })
            assertNull(model.state.value.error)
            assertNull(model.state.value.moreError)
        }
    }

    @Test fun reorderingAndRenamingTabsKeepsCurrentPageAndCachedCursorsWithoutRequests() = runTest {
        fixture {
            val model = feed(listOf(category, tag, latest))
            model.idle()
            model.more()
            model.idle()
            model.select(tag.key)
            model.idle()
            model.more()
            model.idle()
            val before = model.state.value
            val cachedBefore = model.pages.value
            val requestCount = requests.size

            model.configureHomeTabs(listOf(tag.copy(value = "renamed", label = "新标签名"), latest,
                category.copy(label = "新分类名")), null)
            model.idle()
            assertEquals(before, model.state.value)
            assertEquals(cachedBefore, model.pages.value)
            assertEquals(requestCount, requests.size)
            model.select(category.key)
            model.idle()
            assertEquals(listOf(71, 72), model.state.value.topics.map(Topic::id))
            assertEquals("/c/7/l/latest.json?page=2", model.state.value.next)
            assertEquals(requestCount, requests.size)
        }
    }

    @Test fun removingCurrentTabSelectsFirstRemainingTabAndDropsAllDeletedCacheEntries() = runTest {
        fixture {
            val model = feed(listOf(latest, category, tag, hot))
            model.idle()
            model.select(hot.key)
            model.idle()
            model.select(category.key)
            model.idle()
            model.select(tag.key)
            model.idle()
            val requestCount = requests.size

            model.configureHomeTabs(listOf(hot, latest), null)
            model.idle()
            assertEquals(hot.key, model.state.value.filter)
            assertEquals(listOf(2), model.state.value.topics.map(Topic::id))
            assertEquals(requestCount, requests.size)
            assertFalse("删除的非当前页必须释放缓存", model.pages.value.containsKey(category.key))
            assertFalse("切页不能把刚删除的当前页重新写回缓存", model.pages.value.containsKey(tag.key))
            assertTrue(model.pages.value.keys.all { it in setOf(hot.key, latest.key) })
        }
    }

    @Test fun removingAnInactiveTabPrunesItsCacheAndKeepsTheCurrentPage() = runTest {
        fixture {
            val model = feed(listOf(category, tag, latest))
            model.idle()
            model.select(tag.key)
            model.idle()
            val before = model.state.value
            val requestCount = requests.size
            assertTrue(model.pages.value.containsKey(category.key))

            model.configureHomeTabs(listOf(latest, tag), null)
            model.idle()
            assertEquals(before, model.state.value)
            assertFalse(model.pages.value.containsKey(category.key))
            assertEquals(requestCount, requests.size)
        }
    }

    @Test fun directoryOnlyInitialConfigurationNeverRequestsTopicsForGuestOrMember() = runTest {
        for (account in listOf(null, alice)) {
            fixture {
                val tabs = listOf(HomeTab.directory())
                val model = feed(tabs, account)
                model.idle()
                model.configureHomeTabs(tabs, account)
                model.refresh()
                model.more()
                model.idle()
                assertEquals("categories", model.state.value.filter)
                assertFalse(model.state.value.loading)
                assertTrue(model.state.value.topics.isEmpty())
                assertTrue(model.pages.value.isEmpty())
                assertTrue(requests.isEmpty())
            }
        }
    }

    @Test fun autoLoadFalseWaitsForConfigurationBeforeLoadingTheConfiguredCustomPage() = runTest {
        fixture {
            val tabs = listOf(category, tag)
            val model = feed(tabs, autoLoad = false)
            model.idle()
            assertTrue(requests.isEmpty())

            model.configureHomeTabs(tabs, null)
            model.idle()
            assertEquals(category.key, model.state.value.filter)
            assertEquals(listOf(71), model.state.value.topics.map(Topic::id))
            assertEquals(listOf("/c/7/l/latest.json", "/site.json"), paths())
            val requestCount = requests.size
            model.configureHomeTabs(tabs, null)
            model.idle()
            assertEquals(requestCount, requests.size)
        }
    }

    @Test fun firstConfigurationOfDeferredFeedSelectsConfiguredFirstTabBeforeRequesting() = runTest {
        fixture {
            val model = feed(autoLoad = false)
            model.idle()
            assertTrue(requests.isEmpty())

            model.configureHomeTabs(listOf(category, tag), null)
            model.idle()
            assertEquals("首次配置不能保留不在新配置中的默认页", category.key, model.state.value.filter)
            assertEquals(listOf(71), model.state.value.topics.map(Topic::id))
            assertFalse(paths().contains("/latest.json"))
            assertEquals(listOf("/c/7/l/latest.json", "/site.json"), paths())
        }
    }

    @Test fun configuringAnotherAccountReplacesTabsAndClearsOldPagesInTheSameTransition() = runTest {
        fixture {
            val privateTab = HomeTab.filter(FeedFilter.BOOKMARKS)
            val model = feed(listOf(category, tag, privateTab), alice)
            model.idle()
            model.select(tag.key)
            model.idle()
            model.select(privateTab.key)
            model.idle()
            assertTrue(model.pages.value.keys.containsAll(listOf(category.key, tag.key)))
            val requestCount = requests.size
            val newCategory = category.copy(value = "8", label = "账号乙分类")

            model.configureHomeTabs(listOf(newCategory, HomeTab.directory()), bob)
            assertEquals(bob.id, model.state.value.accountId)
            assertEquals(newCategory.key, model.state.value.filter)
            assertTrue(model.state.value.topics.isEmpty())
            assertTrue(model.pages.value.isEmpty())
            assertNull(model.state.value.next)
            assertNull(model.state.value.newCount)
            model.idle()
            assertEquals(listOf(81), model.state.value.topics.map(Topic::id))
            assertEquals(listOf("/c/8/l/latest.json", "/site.json"), paths().drop(requestCount))
            val afterSwitch = requests.size
            listOf(category.key, tag.key, privateTab.key).forEach(model::select)
            model.idle()
            assertEquals(afterSwitch, requests.size)
            assertEquals(newCategory.key, model.state.value.filter)
            assertTrue(model.pages.value.isEmpty())
        }
    }

    @Test fun setUserLogoutDropsPrivateAndOldCustomConfigurationAndRestoresGuestTabs() = runTest {
        fixture {
            val privateTab = HomeTab.filter(FeedFilter.BOOKMARKS)
            val model = feed(listOf(category, tag, privateTab), alice)
            model.idle()
            model.select(tag.key)
            model.idle()
            model.select(privateTab.key)
            model.idle()
            val requestCount = requests.size

            assertTrue(model.setUser(null))
            assertNull(model.state.value.accountId)
            assertEquals(latest.key, model.state.value.filter)
            assertTrue(model.state.value.topics.isEmpty())
            assertTrue(model.pages.value.isEmpty())
            assertNull(model.state.value.next)
            model.idle()
            assertEquals(listOf(1), model.state.value.topics.map(Topic::id))
            assertEquals(listOf("/latest.json", "/site.json"), paths().drop(requestCount))
            val afterLogout = requests.size
            (FeedFilter.entries.filter(FeedFilter::personal).map { it.value } + listOf(category.key, tag.key)).forEach(model::select)
            model.idle()
            assertEquals(afterLogout, requests.size)
            assertEquals(latest.key, model.state.value.filter)
            model.select(hot.key)
            model.idle()
            assertEquals(listOf(2), model.state.value.topics.map(Topic::id))
            assertEquals(setOf(latest.key), model.pages.value.keys)
        }
    }

    private class Fixture {
        val requests = CopyOnWriteArrayList<Request>()
        val models = mutableListOf<FeedViewModel>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val path = request.url.encodedPath
            val page = request.url.queryParameter("page")?.toInt() ?: 0
            val body = when {
                path == "/site.json" -> """{"categories":[{"id":7,"name":"开发"}]}"""
                path.endsWith("/topic-tracking-state.json") -> "[]"
                path == "/c/7/l/latest.json" -> topicPage(71 + page, path, page)
                path == "/c/8/l/latest.json" -> topicPage(81 + page, path, page)
                path == "/tag/kotlin/7.json" -> topicPage(171 + page, path, page)
                path == "/latest.json" -> topicPage(1, path, 2)
                path == "/hot.json" -> topicPage(2, path, 2)
                path == "/bookmarks.json" -> topicPage(3, path, 0)
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        private val repository = CommunityRepository(DiscourseClient(NoCookies, http = http))

        fun feed(tabs: List<HomeTab>? = null, account: Member? = null, autoLoad: Boolean = true) =
            FeedViewModel(repository, initialUser = account, initialHomeTabs = tabs, autoLoad = autoLoad).also(models::add)

        fun paths() = requests.map { request ->
            request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()
        }

        companion object {
            private fun topicPage(id: Int, path: String, page: Int): String {
                val next = if (page < 2) "\"$path?page=${page + 1}\"" else "null"
                return """{"topic_list":{"topics":[{"id":$id,"title":"话题$id"}],"more_topics_url":$next}}"""
            }
        }
    }

    private suspend fun FeedViewModel.idle() = withContext(Dispatchers.Default) {
        withTimeout(5000) { viewModelScope.coroutineContext.job.children.toList().joinAll() }
    }

    private suspend fun TestScope.fixture(block: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
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
}
