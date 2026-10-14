package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.topic.TopicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class TopicVisitHistoryTest {
    private val topic = """{"id":12,"title":"访问记录夹具","highest_post_number":1,"post_stream":{"stream":[101],"posts":[{"id":101,"post_number":1,"user_id":2,"username":"author","cooked":"<p>尚未累计阅读时长</p>"}]}}"""

    private suspend fun TopicViewModel.loaded() = withContext(Dispatchers.IO) {
        withTimeout(5000) { state.first { !it.loading } }.also { assertNull(it.error) }
    }

    @Test fun loggedInOpeningAppearsInServerHistoryWithoutPretendingPostsWereRead() = runTest {
        fixture { repository, requests, models ->
            val state = TopicViewModel(repository, 12, viewerId = 7).also(models::add).loaded()
            val first = requests.first { it.requestUrl!!.encodedPath == "/t/12.json" }
            assertEquals("true", first.requestUrl!!.queryParameter("track_visit"))
            assertEquals(listOf(12), repository.personalTopics("fixture", AccountDestination.HISTORY).topics.map { it.id })
            assertTrue(state.readNumbers.isEmpty())
            assertTrue(requests.all { it.method == "GET" })
            assertFalse(requests.any { it.path!!.contains("timings") })
        }
    }

    @Test fun guestAndRepositoryPreviewNeverCreateVisitHistory() = runTest {
        fixture { repository, requests, models ->
            repository.detail(12, 1)
            TopicViewModel(repository, 12, viewerId = null).also(models::add).loaded()
            assertTrue(requests.filter { it.requestUrl!!.encodedPath.startsWith("/t/") }
                .all { it.requestUrl!!.queryParameter("track_visit") == null })
            assertTrue(repository.personalTopics("fixture", AccountDestination.HISTORY).topics.isEmpty())
        }
    }

    @Test fun refreshDoesNotRepeatVisitButReopeningRecordsANewVisit() = runTest {
        fixture { repository, requests, models ->
            val vm = TopicViewModel(repository, 12, viewerId = 7).also(models::add)
            vm.loaded(); vm.refresh(); vm.loaded()
            TopicViewModel(repository, 12, viewerId = 7).also(models::add).loaded()
            val details = requests.filter { it.requestUrl!!.encodedPath == "/t/12.json" }
            assertEquals(listOf("true", null, "true"), details.map { it.requestUrl!!.queryParameter("track_visit") })
        }
    }

    @Test fun explicitFloorRecordsVisitWithoutChangingItsNavigationTarget() = runTest {
        fixture { repository, requests, models ->
            val state = TopicViewModel(repository, 12, number = 1, viewerId = 7).also(models::add).loaded()
            assertEquals(1, state.targetNumber)
            assertEquals("true", requests.first { it.requestUrl!!.encodedPath == "/t/12/1.json" }
                .requestUrl!!.queryParameter("track_visit"))
        }
    }

    private suspend fun TestScope.fixture(block: suspend (CommunityRepository, List<RecordedRequest>, MutableList<TopicViewModel>) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val visited = AtomicBoolean(false)
        val models = mutableListOf<TopicViewModel>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val url = request.requestUrl!!
                    return when (url.encodedPath) {
                        "/t/12.json", "/t/12/1.json" -> {
                            if (url.queryParameter("track_visit") == "true") visited.set(true)
                            MockResponse().setBody(topic)
                        }
                        "/read.json" -> MockResponse().setBody("""{"topic_list":{"topics":${if (visited.get()) "[{\"id\":12,\"title\":\"访问记录夹具\"}]" else "[]"}}}""")
                        "/u/author.json" -> MockResponse().setBody("""{"user":{"id":2,"username":"author"}}""")
                        else -> MockResponse().setResponseCode(404).setBody("{}")
                    }
                }
            }
            server.start()
            try { block(CommunityRepository(DiscourseClient(NoCookies, server.url("/"))), requests, models) }
            finally {
                models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                Dispatchers.resetMain()
            }
        }
    }
}
