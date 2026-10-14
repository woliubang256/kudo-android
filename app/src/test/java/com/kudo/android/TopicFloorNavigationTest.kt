package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*
import okio.Timeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class TopicFloorNavigationTest {
    private fun post(number: Int, parent: Int? = null) = """{"id":$number,"post_number":$number,"user_id":2,"username":"author","cooked":"<p>楼层$number</p>","reply_to_post_number":$parent}"""
    private fun topic(posts: String = post(1)) = """{"id":5,"title":"跳楼","highest_post_number":100,"post_stream":{"stream":[1,20,80,100],"posts":[$posts]}}"""
    private fun streamedTopic(posts: String, highest: Int = 120) =
        """{"id":5,"title":"far jump","highest_post_number":$highest,"post_stream":{"stream":[${(1..highest).joinToString(",")}],"posts":[$posts]}}"""
    private suspend fun TopicViewModel.await(description: String = "topic state", predicate: (TopicState) -> Boolean) = withContext(Dispatchers.IO) {
        try { withTimeout(5000) { state.first(predicate) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out waiting for $description; state=${state.value}", error) }
    }

    @Test fun highestFloorUsesTheServerNumberInsteadOfTheStreamCount() {
        val detail = DiscourseParser.detail(JSONObject(topic()))
        assertEquals(100, detail.highestPostNumber); assertEquals(4, detail.stream.size)
        val fallback = DiscourseParser.detail(JSONObject(topic(post(80))).apply { remove("highest_post_number") })
        assertEquals(80, fallback.highestPostNumber)
    }

    @Test fun loadedAndRepeatedTargetsDoNotFetchAgainAndRefreshKeepsTheNewTarget() = runTest {
        withTopic({ null }) { vm, paths ->
            vm.await { !it.loading && it.authorProfile != null }
            val before = paths.size
            vm.jumpTo(1); vm.await { it.targetRevision == 1L }
            vm.jumpTo(1); vm.await { it.targetRevision == 2L }
            assertEquals(before, paths.size)
            vm.refresh(); vm.await { !it.loading }
            assertTrue(paths.contains("GET /t/5/1.json"))
            assertEquals(1, vm.state.value.targetNumber)
        }
    }

    @Test fun unloadedNestedTargetMergesTheWindowAndResolvesItsParentWithoutReadingWrites() = runTest {
        withTopic({ request -> when (request.requestUrl!!.encodedPath) {
            "/t/5/80.json" -> MockResponse().setBody(topic(post(80, 20)))
            "/t/5/20.json" -> MockResponse().setBody(topic(post(20)))
            else -> null
        } }) { vm, paths ->
            vm.await { !it.loading }; vm.jumpTo(80)
            val result = vm.await { it.targetNumber == 80 && !it.contextLoading && it.allPosts.any { post -> post.number == 20 } }
            assertEquals(listOf(1, 20, 80), result.allPosts.map(Post::number).sorted())
            assertEquals(80, result.threads.single { it.root.number == 20 }.replies.single().number)
            assertEquals(100, result.detail!!.highestPostNumber)
            assertTrue(result.readNumbers.isEmpty()); assertTrue(paths.all { it.startsWith("GET ") })
        }
    }

    @Test fun loadingAfterAFarJumpDoesNotInsertTheFirstPageBeforeTheTarget() = runTest {
        withTopic({ request ->
            when {
                request.requestUrl!!.encodedPath == "/t/5.json" -> MockResponse().setBody(streamedTopic("${post(1)},${post(2)}"))
                request.requestUrl!!.encodedPath == "/t/5/100.json" -> MockResponse().setBody(streamedTopic(post(100)))
                request.path?.startsWith("/t/5/posts.json") == true -> {
                    val ids = request.requestUrl?.queryParameterValues("post_ids[]").orEmpty().mapNotNull { value -> value?.toIntOrNull() }
                    MockResponse().setBody(streamedTopic(ids.joinToString(",") { post(it) }))
                }
                else -> null
            }
        }) { vm, _ ->
            vm.await { !it.loading }
            vm.jumpTo(100)
            vm.await { it.targetNumber == 100 && !it.jumping }
            vm.more()
            val result = vm.await { !it.loadingMore && it.detail?.posts?.any { post -> post.number > 100 } == true }
            assertFalse("far jump must not insert the first page before the target", result.detail!!.posts.any { it.number in 3..22 })
            assertTrue(result.detail.posts.any { it.number == 101 })
        }
    }

    @Test fun jumpingToACachedPreviewLoadsAContinuousWindowInsteadOfAppendingOneFloor() = runTest {
        withTopic({ request -> when {
            request.requestUrl!!.encodedPath == "/t/5.json" -> MockResponse().setBody(streamedTopic((1..20).joinToString(",") {
                if (it == 2) post(it).replace("\"cooked\"", "\"reply_count\":1,\"cooked\"") else post(it)
            }))
            request.path == "/posts/2/replies.json?after=1" -> MockResponse().setBody("[${post(108, 2)}]")
            request.requestUrl!!.encodedPath == "/t/5/108.json" -> MockResponse().setBody(streamedTopic((98..117).joinToString(",") { post(it) }))
            request.path?.startsWith("/t/5/posts.json") == true -> {
                val ids = request.requestUrl!!.queryParameterValues("post_ids[]").orEmpty().map { it!!.toInt() }
                MockResponse().setBody(streamedTopic(ids.joinToString(",") { post(it) }))
            }
            else -> null
        } }) { vm, paths ->
            val initial = vm.await { !it.loading }
            vm.loadReplies(initial.detail!!.posts.first { it.number == 2 })
            vm.await { it.related.any { post -> post.number == 108 } }
            vm.jumpTo(108)
            val result = vm.await { it.targetNumber == 108 && !it.jumping }
            assertTrue(paths.contains("GET /t/5/108.json"))
            assertEquals((78..120).toList(), result.detail!!.posts.map(Post::number))
            assertTrue(result.allPosts.any { it.number == 2 })
            val prefetchPaths = paths.filter { it.startsWith("GET /t/5/posts.json") }
            assertEquals("jump only prefetches its two neighboring pages", 2, prefetchPaths.size)
            assertFalse("jump must not download the entire skipped interval", result.detail.posts.any { it.number in 3..77 })
            vm.previous()
            val backward = vm.await { !it.loadingPrevious && it.detail!!.posts.first().number == 58 }
            assertEquals((58..120).toList(), backward.detail!!.posts.map(Post::number))
            assertTrue(backward.pageIds(previous = false).isEmpty())
        }
    }

    @Test fun failedBackwardPageKeepsTheWindowAndRetrySkipsOnlyUnavailableIds() = runTest {
        val pages = AtomicInteger()
        withTopic({ request -> when {
            request.requestUrl!!.encodedPath == "/t/5/100.json" -> MockResponse().setBody(streamedTopic((98..117).joinToString(",") { post(it) }))
            request.path?.startsWith("/t/5/posts.json") == true -> {
                val ids = request.requestUrl!!.queryParameterValues("post_ids[]").orEmpty().map { it!!.toInt() }
                if (78 in ids && pages.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("{}")
                else MockResponse().setBody(streamedTopic(ids.filter { it != 90 }.joinToString(",") { post(it) }))
            }
            else -> null
        } }) { vm, _ ->
            vm.await { !it.loading }; vm.jumpTo(100)
            val failed = vm.await { !it.jumping && it.targetNumber == 100 }
            assertNotNull("neighbor failure is retryable without failing the jump", failed.previousError)
            assertNull(failed.jumpError)
            assertEquals((98..120).toList(), failed.detail!!.posts.map(Post::number))
            vm.previous()
            val retried = vm.await { !it.loadingPrevious && it.previousError == null && it.detail!!.posts.first().number == 78 }
            assertFalse(retried.detail!!.stream.contains(90))
            assertEquals((78..120).filter { it != 90 }, retried.detail.posts.map(Post::number))
        }
    }

    @Test fun jumpPrefetchesBothNeighborsConcurrentlyAndThenLoadsOnePageAtATime() = runTest {
        val started = CountDownLatch(2); val release = CountDownLatch(1)
        val pages = CopyOnWriteArrayList<List<Int>>()
        withTopic({ request -> when {
            request.requestUrl!!.encodedPath == "/t/5.json" -> MockResponse().setBody(streamedTopic(post(1), 200))
            request.requestUrl!!.encodedPath == "/t/5/108.json" -> MockResponse().setBody(streamedTopic((98..117).joinToString(",") { post(it) }, 200))
            request.path?.startsWith("/t/5/posts.json") == true -> {
                val ids = request.requestUrl!!.queryParameterValues("post_ids[]").orEmpty().map { it!!.toInt() }
                pages += ids
                started.countDown(); release.await(5, TimeUnit.SECONDS)
                MockResponse().setBody(streamedTopic(ids.joinToString(",") { post(it) }, 200))
            }
            else -> null
        } }) { vm, _ -> try {
            vm.await { !it.loading }; vm.jumpTo(108)
            assertTrue("both neighbor requests must start without waiting for the other", withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            assertEquals(setOf((78..97).toList(), (118..137).toList()), pages.toSet())
            assertTrue(vm.state.value.jumping)
            release.countDown()
            val jumped = vm.await { !it.jumping && it.targetNumber == 108 }
            assertEquals((78..137).toList(), jumped.detail!!.posts.map(Post::number))
            vm.more()
            val forward = vm.await { !it.loadingMore && it.detail!!.posts.last().number == 157 }
            assertEquals((138..157).toList(), pages.last())
            assertEquals((78..157).toList(), forward.detail!!.posts.map(Post::number))
            assertTrue(pages.all { it.size <= 20 })
        } finally { release.countDown() } }
    }

    @Test fun laterBackwardPageFailureKeepsTheWindowAndCanBeRetried() = runTest {
        val attempts = AtomicInteger()
        withTopic({ request -> when {
            request.requestUrl!!.encodedPath == "/t/5/100.json" -> MockResponse().setBody(streamedTopic((98..117).joinToString(",") { post(it) }))
            request.path?.startsWith("/t/5/posts.json") == true -> {
                val ids = request.requestUrl!!.queryParameterValues("post_ids[]").orEmpty().map { it!!.toInt() }
                if (58 in ids && attempts.incrementAndGet() == 1) MockResponse().setResponseCode(503).setBody("{}")
                else MockResponse().setBody(streamedTopic(ids.joinToString(",") { post(it) }))
            }
            else -> null
        } }) { vm, _ ->
            vm.await { !it.loading }; vm.jumpTo(100)
            val initial = vm.await { !it.jumping && it.targetNumber == 100 }.detail!!
            vm.previous()
            val failed = vm.await { it.previousError != null && !it.loadingPrevious }
            assertEquals(initial, failed.detail)
            assertNull(failed.moreError)
            vm.previous()
            val retried = vm.await { !it.loadingPrevious && it.previousError == null && it.detail!!.posts.first().number == 58 }
            assertEquals((58..120).toList(), retried.detail!!.posts.map(Post::number))
        }
    }

    @Test fun neighborPagesUseStreamIdsRatherThanFloorNumbersOrCachedPreviews() {
        val ids = (1..100).map { 5000 + it * 3 }
        val loaded = (41..60).map { number -> DiscourseParser.post(JSONObject(post(number * 2)).put("id", ids[number - 1])) }
        val detail = DiscourseParser.detail(JSONObject(streamedTopic(post(1))))
            .copy(posts = loaded, stream = ids)
        val state = TopicState(detail = detail, related = listOf(loaded.first().copy(id = ids[39], number = 80)))
        assertEquals(ids.subList(20, 40), state.pageIds(previous = true))
        assertEquals(ids.subList(60, 80), state.pageIds(previous = false))
    }

    @Test fun missingAndForbiddenFloorsKeepTheCurrentContentAndExposeAnError() = runTest {
        withTopic({ request -> when (request.requestUrl!!.encodedPath) {
            "/t/5/80.json" -> MockResponse().setBody(topic(post(79)))
            "/t/5/100.json" -> MockResponse().setResponseCode(403).setBody("""{"errors":["不能访问"]}""")
            else -> null
        } }) { vm, _ ->
            val initial = vm.await { !it.loading }.detail
            vm.jumpTo(80); vm.await { !it.jumping && it.jumpError != null }
            assertEquals(initial, vm.state.value.detail); assertNull(vm.state.value.targetNumber)
            vm.jumpTo(100); vm.await { !it.jumping && it.jumpError != null }
            assertEquals(initial, vm.state.value.detail); assertNull(vm.state.value.targetNumber)
        }
    }

    @Test fun aSupersededJumpCannotReplaceTheLatestTarget() = runTest {
        val first = CompletableDeferred<ControlledCall>()
        val latest = CompletableDeferred<ControlledCall>()
        val calls = Call.Factory { request -> ControlledCall(request) { call ->
            when (request.url.encodedPath) {
                "/t/5/80.json" -> first.complete(call)
                "/t/5/100.json" -> latest.complete(call)
                "/t/5.json" -> call.respond(topic())
                "/t/5/posts.json" -> call.respond("""{"post_stream":{"posts":[]}}""")
                "/u/author.json" -> call.respond("""{"user":{"id":2,"username":"author"}}""")
                else -> error("Unexpected fixture request: ${request.url.encodedPath}")
            }
        } }
        withTopic({ null }, route = { calls }) { vm, _ ->
            vm.await { !it.loading && it.authorProfile != null }
            vm.jumpTo(80)
            val stale = withContext(Dispatchers.IO) { withTimeout(5000) { first.await() } }
            vm.jumpTo(100)
            val current = withContext(Dispatchers.IO) { withTimeout(5000) { latest.await() } }
            assertTrue("superseded HTTP call must be cancelled", stale.isCanceled())
            withContext(Dispatchers.IO) { current.respond(topic(post(100))) }
            vm.await("latest target after cancelling 80") { it.targetNumber == 100 && !it.jumping }
            val revision = vm.state.value.targetRevision
            // A response callback can already be in flight when cancellation happens.
            withContext(Dispatchers.IO) { stale.respond(topic(post(80))) }
            yield()
            assertEquals(100, vm.state.value.targetNumber)
            assertEquals(revision, vm.state.value.targetRevision)
            assertFalse(vm.state.value.allPosts.any { it.number == 80 })
        }
    }

    @Test fun aJumpWindowThatCompletesAParentClearsTheCancelledContextLoadingState() = runTest {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        withTopic({ request -> when (request.requestUrl!!.encodedPath) {
            "/t/5.json" -> MockResponse().setBody(topic("${post(1)},${post(80, 20)}"))
            "/t/5/20.json" -> {
                if (calls.incrementAndGet() == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                MockResponse().setBody(topic(post(20)))
            }
            else -> null
        } }) { vm, _ -> try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            vm.jumpTo(20)
            val result = vm.await { it.targetNumber == 20 && !it.jumping && !it.contextLoading }
            assertFalse("新窗口已补齐父楼层，不能残留已取消请求的加载状态", result.contextLoading)
        } finally { release.countDown() } }
    }

    private class ControlledCall(private val request: Request, private val onEnqueue: (ControlledCall) -> Unit) : Call {
        private var callback: Callback? = null
        @Volatile private var cancelled = false
        override fun request() = request
        override fun enqueue(responseCallback: Callback) {
            check(callback == null)
            callback = responseCallback
            onEnqueue(this)
        }
        fun respond(body: String) {
            checkNotNull(callback).onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build())
        }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = callback != null
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ControlledCall(request, onEnqueue)
        override fun execute(): Response = error("Fixture expects asynchronous HTTP calls")
    }

    private suspend fun TestScope.withTopic(response: (RecordedRequest) -> MockResponse?, route: (OkHttpClient) -> Call.Factory = { it }, block: suspend (TopicViewModel, List<String>) -> Unit) {
        val paths = CopyOnWriteArrayList<String>()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
            Dispatchers.setMain(main)
            try {
                MockWebServer().use { server ->
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            paths += "${request.method} ${request.path}"
                            return response(request) ?: when {
                                request.requestUrl!!.encodedPath == "/t/5.json" || request.requestUrl!!.encodedPath == "/t/5/1.json" -> MockResponse().setBody(topic())
                                request.path!!.startsWith("/u/") -> MockResponse().setBody("""{"user":{"id":2,"username":"author"}}""")
                                else -> MockResponse().setResponseCode(404).setBody("{}")
                            }
                        }
                    }
                    server.start()
                    val vm = withContext(main) {
                        TopicViewModel(CommunityRepository(DiscourseClient(NoCookies, server.url("/"), route = route)), 5, viewerId = 9)
                    }
                    try { withContext(main) { block(vm, paths) } }
                    finally { withContext(NonCancellable) { vm.viewModelScope.coroutineContext.job.cancelAndJoin() } }
                }
            } finally { Dispatchers.resetMain() }
        }
    }
}
