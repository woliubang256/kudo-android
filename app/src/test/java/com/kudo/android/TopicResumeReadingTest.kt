package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TopicResumeReadingTest {
    private fun post(number: Int) = """{"id":${10_000 + number},"post_number":$number,"user_id":2,"username":"author","cooked":"<p>楼层$number</p>"}"""
    private fun topic(numbers: IntRange = 1..20, progress: Int? = 7, highest: Int = 600) =
        """{"id":5,"title":"续读","highest_post_number":$highest,"last_read_post_number":$progress,"post_stream":{"stream":[${(1..highest).joinToString(",") { (10_000 + it).toString() }}],"posts":[${numbers.joinToString(",") { post(it) }}]}}"""
    private suspend fun TopicViewModel.loaded() = withContext(Dispatchers.IO) {
        withTimeout(5000) { state.first { !it.loading } }
    }

    @Test fun normalEntryResumesInTheLoadedPageWithoutAnotherWindowOrReadingWrites() = runTest {
        withTopic { vm, paths, _ ->
            val result = vm.loaded()
            assertEquals(8, result.targetNumber)
            assertTrue(result.detail!!.posts.any { it.number == 8 })
            assertEquals(listOf("GET /t/5.json?track_visit=true"), paths.filter { it.contains("/t/") })
            assertTrue(result.readNumbers.isEmpty())
            assertTrue(paths.all { it.startsWith("GET ") })
        }
    }

    @Test fun farResumeLoadsOnlyTheTargetAndAdjacentPagesAndSupportsBackwardPaging() = runTest {
        withTopic(progress = 499, response = { request ->
            if (request.requestUrl!!.encodedPath == "/t/5/500.json") MockResponse().setBody(topic(490..509, 499)) else null
        }) { vm, paths, _ ->
            val result = vm.loaded()
            assertEquals(500, result.targetNumber)
            assertEquals((470..529).toList(), result.detail!!.posts.map(Post::number))
            val pages = paths.filter { it.contains("/posts.json") }
            assertEquals(2, pages.size)
            assertEquals(1, paths.count { "track_visit=true" in it })
            assertTrue(pages.any { it.contains("post_ids%5B%5D=10470") })
            assertTrue(pages.any { it.contains("post_ids%5B%5D=10510") })
            assertTrue(result.readNumbers.isEmpty())
            assertTrue(paths.all { it.startsWith("GET ") })
            vm.previous()
            val previous = withContext(Dispatchers.IO) { withTimeout(5000) {
                vm.state.first { !it.loadingPrevious && it.detail!!.posts.first().number == 450 }
            } }
            assertEquals((450..529).toList(), previous.detail!!.posts.map(Post::number))
        }
    }

    @Test fun completedTopicResumesAtTheLastAvailableFloor() = runTest {
        withTopic(progress = 600, response = { request ->
            if (request.requestUrl!!.encodedPath == "/t/5/600.json") MockResponse().setBody(topic(590..600, 600)) else null
        }) { vm, paths, _ ->
            assertEquals(600, vm.loaded().targetNumber)
            assertEquals(1, paths.count { it.contains("/posts.json") })
            assertFalse(paths.any { it.contains("/601.json") })
        }
    }

    @Test fun explicitFloorIncludingTheFirstFloorAlwaysWinsOverServerProgress() = runTest {
        listOf(1, 17).forEach { target ->
            withTopic(number = target, progress = 499) { vm, paths, _ ->
                assertEquals(target, vm.loaded().targetNumber)
                assertEquals(listOf("GET /t/5/$target.json?track_visit=true"), paths.filter { it.contains("/t/") })
            }
        }
    }

    @Test fun firstVisitAndGuestEntryStartAtTheBeginning() = runTest {
        withTopic(progress = null) { vm, _, _ -> assertNull(vm.loaded().targetNumber) }
        withTopic(viewerId = null, progress = 499) { vm, paths, _ ->
            assertNull(vm.loaded().targetNumber)
            assertFalse(paths.any { it.contains("/500.json") })
            assertFalse(paths.any { "track_visit" in it })
        }
    }

    @Test fun reopeningReadsFreshProgressWhileRefreshKeepsTheExistingTarget() = runTest {
        var progress = 7
        withTopic(response = { request ->
            if (request.requestUrl!!.encodedPath == "/t/5.json" || request.requestUrl!!.encodedPath == "/t/5/8.json") MockResponse().setBody(topic(progress = progress)) else null
        }) { vm, paths, reopen ->
            assertEquals(8, vm.loaded().targetNumber)
            progress = 13
            vm.refresh()
            assertEquals(8, vm.loaded().targetNumber)
            assertTrue(paths.contains("GET /t/5/8.json"))
            assertEquals(14, reopen().loaded().targetNumber)
        }
    }

    @Test fun deletedResumeFloorUsesTheNearestAccessibleFloorInTheReturnedWindow() = runTest {
        withTopic(progress = 499, response = { request ->
            if (request.requestUrl!!.encodedPath == "/t/5/500.json") MockResponse().setBody(topic(501..520, 499)) else null
        }) { vm, _, _ ->
            val result = vm.loaded()
            assertEquals(501, result.targetNumber)
            assertTrue(result.detail!!.posts.any { it.number == result.targetNumber })
            assertNull(result.error)
        }
    }

    @Test fun unavailableResumeWindowFallsBackToTheLoadedFirstPage() = runTest {
        listOf(403, 404, 503).forEach { code ->
            withTopic(progress = 499, response = { request ->
                if (request.requestUrl!!.encodedPath == "/t/5/500.json") MockResponse().setResponseCode(code).setBody("{}") else null
            }) { vm, _, _ ->
                val result = vm.loaded()
                assertNull(result.error)
                assertNull(result.targetNumber)
                assertEquals((1..20).toList(), result.detail!!.posts.map(Post::number))
                assertFalse(result.pageIds(previous = false).isEmpty())
            }
        }
    }

    @Test fun neighboringPageFailureDoesNotBlockTheResumeTargetAndCanBeRetried() = runTest {
        var failPrevious = true
        withTopic(progress = 499, response = { request -> when {
            request.requestUrl!!.encodedPath == "/t/5/500.json" -> MockResponse().setBody(topic(490..509, 499))
            request.path?.contains("post_ids%5B%5D=10470") == true && failPrevious -> MockResponse().setResponseCode(503).setBody("{}")
            else -> null
        } }) { vm, _, _ ->
            val result = vm.loaded()
            assertEquals(500, result.targetNumber)
            assertNotNull(result.previousError)
            assertNull(result.moreError)
            assertEquals((490..529).toList(), result.detail!!.posts.map(Post::number))
            failPrevious = false
            vm.previous()
            val retried = withContext(Dispatchers.IO) { withTimeout(5000) {
                vm.state.first { !it.loadingPrevious && it.detail!!.posts.first().number == 470 }
            } }
            assertNull(retried.previousError)
        }
    }

    @Test fun emptyResumeWindowFallsBackWithoutLeavingAnUnreachableTarget() = runTest {
        withTopic(progress = 499, response = { request ->
            if (request.requestUrl!!.encodedPath == "/t/5/500.json") MockResponse().setBody(topic(IntRange.EMPTY, 499)) else null
        }) { vm, _, _ ->
            val result = vm.loaded()
            assertNull(result.targetNumber)
            assertNull(result.error)
            assertEquals((1..20).toList(), result.detail!!.posts.map(Post::number))
        }
    }

    @Test fun refreshDuringResumeCancelsTheOldWindowAndUsesFreshProgress() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var progress = 499
        withTopic(response = { request -> when (request.requestUrl!!.encodedPath) {
            "/t/5.json" -> MockResponse().setBody(topic(progress = progress))
            "/t/5/500.json" -> {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                MockResponse().setBody(topic(490..509, 499))
            }
            else -> null
        } }) { vm, paths, _ -> try {
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            assertTrue(vm.state.value.loading)
            assertNull(vm.state.value.detail)
            progress = 7
            vm.refresh()
            assertEquals(8, vm.loaded().targetNumber)
            release.countDown()
            assertFalse(vm.state.value.detail!!.posts.any { it.number == 500 })
            assertFalse(paths.any { it.contains("/posts.json") })
        } finally { release.countDown() } }
    }

    @Test fun cloudflareOnTheResumeRequestKeepsTheVerificationMessage() = runTest {
        withTopic(progress = 499, response = { request ->
            if (request.requestUrl!!.encodedPath == "/t/5/500.json") MockResponse().setResponseCode(403)
                .setHeader("cf-mitigated", "challenge").setHeader("Content-Type", "text/html")
                .setBody("<html><title>Just a moment...</title></html>") else null
        }) { vm, _, _ ->
            val result = vm.loaded()
            assertNull(result.error)
            assertNull(result.targetNumber)
            assertEquals(CloudflareVerificationMessage, withTimeout(5000) { vm.events.first() })
        }
    }

    private suspend fun TestScope.withTopic(
        number: Int? = null,
        viewerId: Int? = 9,
        progress: Int? = 7,
        response: (RecordedRequest) -> MockResponse? = { null },
        block: suspend (TopicViewModel, List<String>, () -> TopicViewModel) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val paths = CopyOnWriteArrayList<String>()
        val models = mutableListOf<TopicViewModel>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += "${request.method} ${request.path}"
                    return response(request) ?: when {
                        request.path!!.startsWith("/t/5/posts.json") -> {
                            val numbers = request.requestUrl!!.queryParameterValues("post_ids[]").orEmpty().map { it!!.toInt() - 10_000 }
                            MockResponse().setBody("""{"post_stream":{"posts":[${numbers.joinToString(",") { post(it) }}]}}""")
                        }
                        request.requestUrl!!.encodedPath == "/t/5.json" || request.requestUrl!!.encodedPath == "/t/5/$number.json" || request.requestUrl!!.encodedPath == "/t/5/1.json" -> MockResponse().setBody(topic(progress = progress))
                        request.path!!.startsWith("/u/") -> MockResponse().setBody("""{"user":{"id":2,"username":"author"}}""")
                        else -> MockResponse().setResponseCode(404).setBody("{}")
                    }
                }
            }
            server.start()
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            val create = { TopicViewModel(repository, 5, number, viewerId).also { models += it } }
            try { block(create(), paths, create) }
            finally {
                models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                Dispatchers.resetMain()
            }
        }
    }
}
