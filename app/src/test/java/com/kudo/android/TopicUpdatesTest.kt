package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class TopicUpdatesTest {
    @Test fun presenceChangesAreImmediateAndDoNotFetchRepliesOrChangeReading() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val before = f.vm.state.value
            val checks = f.snapshots.get()
            f.total.set(3)
            f.act { f.activities.tryEmit(TopicActivity(replyingUsers = listOf(Member(2, "two", "", ""), Member(7, "self", "", ""), Member(2, "two", "", "")))) }
            f.until { f.vm.state.value.replyingUsers.size == 1 }
            delay(200)
            assertEquals(listOf(2), f.vm.state.value.replyingUsers.map(Member::id))
            assertEquals(checks, f.snapshots.get())
            assertEquals(before.detail, f.vm.state.value.detail)
            assertEquals(before.targetRevision, f.vm.state.value.targetRevision)
            assertEquals(before.readNumbers, f.vm.state.value.readNumbers)
            f.hint()
            f.until { f.vm.state.value.detail!!.posts.size == 3 }
            assertEquals(listOf(2), f.vm.state.value.replyingUsers.map(Member::id))
            f.act { f.activities.tryEmit(TopicActivity(replyingUsers = emptyList())) }
            f.until { f.vm.state.value.replyingUsers.isEmpty() }
        }
    }

    @Test fun pauseClearsPresenceAndResumeOnlyShowsTheNewSubscriptionState() = runBlocking {
        Fixture().use { f ->
            f.ready()
            f.act { f.activities.tryEmit(TopicActivity(replyingUsers = listOf(Member(2, "two", "", "")))) }
            f.until { f.vm.state.value.replyingUsers.isNotEmpty() }
            f.act { f.vm.setUpdatesActive(false) }
            assertTrue(f.vm.state.value.replyingUsers.isEmpty())
            f.act { f.activities.tryEmit(TopicActivity(replyingUsers = listOf(Member(3, "old", "", "")))) }
            f.act { f.vm.setUpdatesActive(true) }
            f.until { f.subscriptions.get() == 2 }
            f.act { f.activities.tryEmit(TopicActivity(replyingUsers = listOf(Member(4, "new", "", "")))) }
            f.until { f.vm.state.value.replyingUsers.map(Member::id) == listOf(4) }
        }
    }

    @Test fun subscriptionFailureClearsPresenceAndLeavesThePageAvailableForResuming() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val before = f.vm.state.value.detail
            f.act { f.activities.tryEmit(TopicActivity(replyingUsers = listOf(Member(2, "two", "", "")))) }
            f.until { f.vm.state.value.replyingUsers.isNotEmpty() }
            f.act { f.failures.tryEmit(IllegalStateException("fixture failure")) }
            f.until { f.vm.state.value.replyingUsers.isEmpty() }
            assertEquals(before, f.vm.state.value.detail)
            assertNull(f.vm.state.value.error)
            f.act { f.vm.setUpdatesActive(true) }
            f.until { f.subscriptions.get() == 2 }
            f.act { f.activities.tryEmit(TopicActivity(replyingUsers = listOf(Member(3, "three", "", "")))) }
            f.until { f.vm.state.value.replyingUsers.map(Member::id) == listOf(3) }
        }
    }

    @Test fun coalescesNewRepliesAndAppendsWithoutResettingExistingPostsOrReadingState() = runBlocking {
        Fixture().use { f ->
            f.ready()
            f.act { f.vm.markRead(setOf(1)) }
            val before = f.vm.state.value
            val checks = f.snapshots.get()
            f.total.set(4)
            f.act { repeat(20) { f.hints.tryEmit(Unit) } }
            f.until { f.vm.state.value.detail!!.posts.size == 4 }
            val after = f.vm.state.value
            assertEquals(checks + 1, f.snapshots.get())
            assertEquals(listOf(listOf(3, 4)), f.postRequests)
            assertEquals(before.detail!!.posts, after.detail!!.posts.take(2))
            assertEquals(setOf(1), after.readNumbers)
            assertEquals(before.targetNumber, after.targetNumber)
            assertEquals(before.targetRevision, after.targetRevision)
        }
    }

    @Test fun readingEarlierPagesExtendsTheDirectoryWithoutInsertingADistantReply() = runBlocking {
        Fixture(initialTotal = 100).use { f ->
            f.ready(); f.total.set(101); f.hint()
            f.until { f.vm.state.value.detail!!.highestPostNumber == 101 }
            assertEquals(listOf(1, 2), f.vm.state.value.detail!!.posts.map(Post::number))
            assertTrue(f.postRequests.isEmpty())
            f.act { f.vm.more() }
            f.until { !f.vm.state.value.loadingMore && f.vm.state.value.detail!!.posts.size > 2 }
            assertEquals((3..22).toList(), f.postRequests.single())
            assertFalse(f.vm.state.value.detail!!.posts.any { it.number == 101 })
        }
    }

    @Test fun aLargeBurstLoadsOnlyOneBatchAndLeavesTheRestToNormalPagination() = runBlocking {
        Fixture().use { f ->
            f.ready(); f.total.set(62); f.hint()
            f.until { f.vm.state.value.detail!!.posts.size == 22 }
            assertEquals((3..22).toList(), f.postRequests.single())
            assertEquals((23..42).toList(), f.vm.state.value.pageIds(previous = false))
            f.act { f.vm.more() }
            f.until { f.vm.state.value.detail!!.posts.size == 42 }
            assertEquals((23..42).toList(), f.postRequests.last())
        }
    }

    @Test fun pausingDiscardsAnInflightCheckAndResumingCatchesUpWithoutANewHint() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val before = f.snapshots.get()
            f.nextSnapshot.set(MockResponse().setBody(Fixture.topic(3)).setBodyDelay(300, TimeUnit.MILLISECONDS))
            f.hint(); f.until { f.snapshots.get() > before }
            f.act { f.vm.setUpdatesActive(false) }
            f.total.set(4); f.hint(); delay(400)
            assertEquals(2, f.vm.state.value.detail!!.posts.size)
            assertEquals(before + 1, f.snapshots.get())
            f.act { f.vm.setUpdatesActive(true) }
            f.until { f.vm.state.value.detail!!.posts.size == 4 }
            assertEquals(2, f.subscriptions.get())
        }
    }

    @Test fun aHintArrivingDuringACheckIsProcessedAfterThatCheck() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val before = f.snapshots.get()
            f.nextSnapshot.set(MockResponse().setBody(Fixture.topic(3)).setBodyDelay(250, TimeUnit.MILLISECONDS))
            f.hint(); f.until { f.snapshots.get() > before }
            f.total.set(4); f.hint()
            f.until { f.vm.state.value.detail!!.posts.size == 4 }
            assertEquals(before + 2, f.snapshots.get())
            assertEquals(listOf(listOf(3), listOf(4)), f.postRequests)
        }
    }

    @Test fun aLateCheckCannotRestoreTheOldWindowAfterJumping() = runBlocking {
        Fixture(initialTotal = 100).use { f ->
            f.ready()
            val before = f.snapshots.get()
            f.nextSnapshot.set(MockResponse().setBody(Fixture.topic(101)).setBodyDelay(250, TimeUnit.MILLISECONDS))
            f.hint(); f.until { f.snapshots.get() > before }
            f.act { f.vm.jumpTo(80) }
            f.until { !f.vm.state.value.jumping && f.vm.state.value.targetNumber == 80 }
            delay(450)
            val state = f.vm.state.value
            assertEquals(80, state.targetNumber)
            assertEquals(1L, state.targetRevision)
            assertEquals(60, state.detail!!.posts.first().number)
            assertFalse(state.detail.posts.any { it.number in 1..59 })
        }
    }

    @Test fun failedChecksKeepThePageAndRetryWithoutResettingReading() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val before = f.vm.state.value
            val checks = f.snapshots.get()
            f.nextSnapshot.set(MockResponse().setResponseCode(500).setBody("{}"))
            f.total.set(3); f.hint()
            f.until { f.snapshots.get() > checks }
            assertEquals(before.detail, f.vm.state.value.detail)
            assertFalse(f.vm.state.value.loading)
            assertNull(f.vm.state.value.error)
            f.until { f.vm.state.value.detail!!.posts.size == 3 }
        }
    }

    @Test fun aLateLiveResponseCannotRollBackACompletedManualRefresh() = runBlocking {
        Fixture().use { f ->
            f.ready()
            val checks = f.snapshots.get()
            f.nextSnapshot.set(MockResponse().setBody(Fixture.topic(3)).setBodyDelay(250, TimeUnit.MILLISECONDS))
            f.hint(); f.until { f.snapshots.get() > checks }
            f.total.set(4)
            f.act { f.vm.refresh() }
            f.until { !f.vm.state.value.loading && f.vm.state.value.detail!!.highestPostNumber == 4 }
            delay(400)
            assertEquals(4, f.vm.state.value.detail!!.highestPostNumber)
            assertEquals(listOf(1, 2), f.vm.state.value.detail!!.posts.map(Post::number))
            assertEquals((3..4).toList(), f.vm.state.value.pageIds(previous = false))
        }
    }

    private class Fixture(initialTotal: Int = 2) : AutoCloseable {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val hints = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val activities = MutableSharedFlow<TopicActivity>(extraBufferCapacity = 16)
        val failures = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
        val total = AtomicInteger(initialTotal)
        val snapshots = AtomicInteger()
        val subscriptions = AtomicInteger()
        val nextSnapshot = AtomicReference<MockResponse?>(null)
        val postRequests = CopyOnWriteArrayList<List<Int>>()
        private val server = MockWebServer()
        val vm: TopicViewModel
        init {
            Dispatchers.setMain(main)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.requestUrl!!.encodedPath == "/t/5.json" -> {
                        snapshots.incrementAndGet()
                        nextSnapshot.getAndSet(null) ?: MockResponse().setBody(topic(total.get()))
                    }
                    request.path!!.startsWith("/t/5/posts.json") -> {
                        val ids = request.requestUrl!!.queryParameterValues("post_ids[]").map { it!!.toInt() }
                        postRequests += ids
                        MockResponse().setBody("""{"post_stream":{"posts":[${ids.joinToString(",") { post(it) }}]}}""")
                    }
                    request.path!!.startsWith("/t/5/") -> {
                        val number = request.path!!.substringAfter("/t/5/").substringBefore('.').toInt()
                        MockResponse().setBody(topic(total.get(), number..minOf(number + 19, total.get())))
                    }
                    request.path!!.startsWith("/u/") -> MockResponse().setBody("""{"user":{"id":2,"username":"author"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            vm = TopicViewModel(CommunityRepository(DiscourseClient(NoCookies, server.url("/"))), 5, viewerId = 7,
                updates = object : TopicUpdates {
                    override fun observeTopic(user: Member?, topicId: Int): Flow<Unit> {
                        check(topicId == 5)
                        return hints.onStart { subscriptions.incrementAndGet() }
                    }
                    override fun observeActivity(user: Member?, topicId: Int): Flow<TopicActivity> = merge(
                        observeTopic(user, topicId).map { TopicActivity(checkReplies = true) },
                        activities, failures.map { throw it })
                }, updateIntervalMillis = 80)
        }
        suspend fun act(block: () -> Unit) = withContext(main) { block() }
        suspend fun hint() = act { hints.tryEmit(Unit); Unit }
        suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!withContext(main) { condition() }) delay(10) }
        suspend fun ready() {
            until { !vm.state.value.loading }
            act { vm.setUpdatesActive(true) }
            until { subscriptions.get() == 1 && snapshots.get() >= 2 }
            delay(120)
        }
        override fun close() {
            try { runBlocking { withContext(main) { vm.viewModelScope.coroutineContext.job.cancelAndJoin() } } }
            finally { server.shutdown(); Dispatchers.resetMain(); main.close() }
        }
        companion object {
            fun post(number: Int) = """{"id":$number,"post_number":$number,"user_id":2,"username":"author","cooked":"<p>post-$number</p>"}"""
            fun topic(total: Int, loaded: IntRange = 1..2) =
                """{"id":5,"title":"live topic","highest_post_number":$total,"post_stream":{"stream":[${(1..total).joinToString(",")}],"posts":[${loaded.joinToString(",") { post(it) }}]}}"""
        }
    }
}
