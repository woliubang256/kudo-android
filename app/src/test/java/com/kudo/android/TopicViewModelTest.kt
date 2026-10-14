package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.topic.TopicState
import com.kudo.android.ui.topic.TopicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TopicViewModelTest {
    @Test fun refreshAfterDeletionRemovesCachedBodyAndPostActions() = runTest {
        var unavailable = false
        withTopic(response = { request ->
            if (unavailable && request.requestUrl!!.encodedPath == "/t/5.json") MockResponse().setResponseCode(404).setBody("{}") else null
        }) { vm, _ ->
            vm.awaitState { !it.loading && it.authorProfile != null }
            assertNotNull(vm.state.value.detail)
            unavailable = true
            vm.refresh()
            val state = vm.awaitState { !it.loading && it.error != null }
            assertEquals(FailureKind.NOT_FOUND, (state.error as ApiFailure).kind)
            assertNull(state.detail)
            assertNull(state.authorProfile)
            assertTrue(state.allPosts.isEmpty())
        }
    }
    private val original = postJson(2, false)
    private fun postJson(count: Int, active: Boolean) = """{"id":10,"post_number":1,"user_id":2,"username":"author","cooked":"<p>正文</p>","actions_summary":[{"id":2,"count":$count,"can_act":${!active},"can_undo":$active}],"reactions":[{"id":"heart","type":"emoji","count":$count}],"reaction_users_count":$count,"current_user_reaction":${if (active) """{"id":"heart","type":"emoji","can_undo":true}""" else "null"}}"""
    private val topic get() = """{"id":5,"title":"测试话题","valid_reactions":["heart","distorted_face"],"post_stream":{"stream":[10,11],"posts":[$original]}}"""

    private suspend fun TopicViewModel.awaitState(predicate: (TopicState) -> Boolean): TopicState =
        withContext(Dispatchers.IO) { withTimeout(5_000) { state.first(predicate) } }

    @Test fun boostWaitsForConfirmationAndSharesThePostMutationLock() = runTest {
        val gate = CountDownLatch(1)
        val writes = AtomicInteger()
        withTopic(response = { request -> when {
            request.requestUrl!!.encodedPath == "/t/5.json" -> MockResponse().setBody(topic.replace("\"post_number\":1", "\"post_number\":1,\"can_boost\":true"))
            request.path == "/emojis.json" -> MockResponse().setBody("""{"custom":[{"name":"test","url":"/test.png"}]}""")
            request.path!!.contains("/boosts") -> {
                writes.incrementAndGet(); gate.await(5, TimeUnit.SECONDS)
                MockResponse().setBody("""{"id":7,"cooked":"<p>加油</p>","user":{"id":9,"username":"booster"}}""")
            }
            else -> null
        } }) { vm, _ ->
            try {
                val post = vm.awaitState { !it.loading }.detail!!.originalPost!!
                vm.loadBoostRules(); vm.awaitState { it.boostEmojis != null }
                var accepted = 0
                vm.boost(post, "加油") { accepted++ }
                vm.boost(post, "重复") { accepted++ }; vm.like(post)
                assertEquals(0, accepted); assertTrue(vm.state.value.detail!!.originalPost!!.boosts.isEmpty())
                gate.countDown()
                val updated = vm.awaitState { it.busy.isEmpty() }.detail!!
                assertEquals(1, accepted); assertEquals(1, writes.get())
                assertEquals(7, updated.originalPost!!.boosts.single().id)
                assertEquals(2, updated.originalPost!!.reactionCount)
            } finally { gate.countDown() }
        }
    }

    @Test fun localReadFeedbackSurvivesLatePageAndRefreshWithoutMarkingSkippedFloors() = runTest {
        val gate = CountDownLatch(1)
        withTopic(response = { request ->
            if (request.path!!.contains("/posts.json")) {
                gate.await(5, TimeUnit.SECONDS)
                MockResponse().setBody("""{"post_stream":{"posts":[{"id":11,"post_number":2,"read":false}]}}""")
            } else null
        }) { vm, _ ->
            try {
                vm.awaitState { !it.loading }
                vm.more()
                vm.markRead(setOf(1, 2, 38))
                assertEquals(setOf(1), vm.state.value.readNumbers)
                gate.countDown()
                val paged = vm.awaitState { !it.loadingMore }
                assertEquals(setOf(1), paged.readNumbers)
                assertEquals(false, paged.allPosts.first { it.number == 2 }.read)
                vm.markRead(setOf(2)); vm.refresh()
                assertEquals(setOf(1, 2), vm.awaitState { !it.loading }.readNumbers)
            } finally { gate.countDown() }
        }
    }

    @Test fun paginationPromotesAcceptedAnswerPostIntoTopicSolutions() = runTest {
        val initial = """{"id":5,"title":"分页解决方案","post_stream":{"stream":[10,11,12],"posts":[$original]}}"""
        withTopic(initial = initial, response = { request ->
            if (request.path!!.contains("/posts.json")) MockResponse().setBody(
                """{"post_stream":{"posts":[{"id":11,"post_number":2,"user_id":3,"username":"reply"},{"id":12,"post_number":3,"user_id":4,"username":"helper","accepted_answer":true}]}}"""
            ) else null
        }) { vm, _ ->
            vm.awaitState { !it.loading }
            vm.more()
            val loaded = vm.awaitState { !it.loadingMore }
            assertEquals(listOf(3), loaded.detail!!.solutions.map { it.postNumber })
            assertTrue(loaded.detail.posts.single { it.number == 3 }.isSolution)
        }
    }

    @Test fun reactionUsesTheServerConfirmedPostInsteadOfLocalArithmetic() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.requestUrl!!.encodedPath == "/t/5.json" -> MockResponse().setBody(topic)
                    request.path == "/session/csrf" -> MockResponse().setBody("""{"csrf":"test"}""")
                    request.path!!.endsWith("/toggle.json") -> MockResponse().setBody(postJson(27, true))
                    request.path!!.startsWith("/u/") -> MockResponse().setBody("""{"user":{"id":2,"username":"author","can_follow":true,"is_followed":false}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val vm = TopicViewModel(CommunityRepository(DiscourseClient(NoCookies, server.url("/"))), 5)
            try {
                val initial = vm.awaitState { !it.loading }.detail!!.posts.single()
                vm.like(initial)
                val after = vm.awaitState { it.busy.isEmpty() }.detail!!.posts.single()
                assertEquals(27, after.likes)
                assertEquals(27, after.reactionCount)
                assertEquals("heart", after.activeReaction)
            } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }

    @Test fun delayedPaginationCannotOverwriteACompletedReaction() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val releasePage = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.requestUrl!!.encodedPath == "/t/5.json" -> MockResponse().setBody(topic)
                    request.path == "/session/csrf" -> MockResponse().setBody("""{"csrf":"test"}""")
                    request.path!!.endsWith("/toggle.json") -> MockResponse().setBody(postJson(27, true))
                    request.path!!.contains("/posts.json") -> {
                        releasePage.await(5, TimeUnit.SECONDS)
                        MockResponse().setBody("""{"post_stream":{"posts":[{"id":11,"post_number":2,"user_id":3,"username":"reply"}]}}""")
                    }
                    request.path!!.startsWith("/u/") -> MockResponse().setBody("""{"user":{"id":2,"username":"author","can_follow":true,"is_followed":false}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val vm = TopicViewModel(CommunityRepository(DiscourseClient(NoCookies, server.url("/"))), 5)
            try {
                val initial = vm.awaitState { !it.loading }.detail!!.posts.single()
                vm.more()
                vm.like(initial)
                val reacted = vm.awaitState { it.busy.isEmpty() }.detail!!.posts.first()
                releasePage.countDown()
                val after = vm.awaitState { !it.loadingMore }.detail!!.posts.first()
                assertEquals("翻页不得恢复写操作前的赞数", reacted.likes, after.likes)
                assertEquals(reacted.activeReaction, after.activeReaction)
            } finally { releasePage.countDown(); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }

    @Test fun rapidTapsAreSerializedAndAnOldSnapshotStillCancelsTheCurrentReaction() = runTest {
        val gate = CountDownLatch(1)
        val writes = AtomicInteger()
        withTopic(response = { request ->
            if (request.path!!.endsWith("/toggle.json")) {
                val attempt = writes.incrementAndGet()
                if (attempt == 1) gate.await(5, TimeUnit.SECONDS)
                MockResponse().setBody(postJson(if (attempt == 1) 27 else 26, attempt == 1))
            } else null
        }) { vm, _ ->
            try {
                val initial = vm.awaitState { !it.loading }.detail!!.posts.single()
                vm.like(initial)
                vm.like(initial)
                vm.refresh()
                assertFalse("写入期间不启动整页刷新", vm.state.value.loading)
                gate.countDown()
                vm.awaitState { it.busy.isEmpty() }
                assertEquals(1, writes.get())
                vm.like(initial)
                val after = vm.awaitState { it.busy.isEmpty() }.detail!!.posts.single()
                assertEquals(2, writes.get())
                assertNull(after.activeReaction)
                assertEquals(26, after.reactionCount)
            } finally { gate.countDown() }
        }
    }

    @Test fun failedReactionKeepsThePostAndFollowOnlyChangesAfterConfirmedSuccess() = runTest {
        val follows = AtomicInteger()
        withTopic(response = { request ->
            when {
                request.path!!.endsWith("/toggle.json") -> MockResponse().setResponseCode(429).setBody("""{"errors":["稍后重试"]}""")
                request.path!!.startsWith("/follow/") -> if (follows.incrementAndGet() == 1)
                    MockResponse().setResponseCode(500).setBody("""{"errors":["关注失败"]}""") else MockResponse().setBody("""{"success":"OK"}""")
                else -> null
            }
        }) { vm, _ ->
            val loaded = vm.awaitState { !it.loading && it.authorProfile != null }
            val initial = loaded.detail!!.posts.single()
            vm.react(initial, "distorted_face")
            assertEquals(initial, vm.awaitState { it.busy.isEmpty() }.detail!!.posts.single())
            advanceTimeBy(15_000)
            vm.follow()
            assertFalse(vm.awaitState { !it.followBusy }.authorProfile!!.isFollowed)
            vm.follow()
            assertTrue(vm.awaitState { !it.followBusy }.authorProfile!!.isFollowed)
            vm.follow()
            assertFalse(vm.awaitState { !it.followBusy }.authorProfile!!.isFollowed)
        }
    }

    @Test fun deepLinkLoadsOriginalForActionsWithoutInsertingItIntoTheReadingStream() = runTest {
        withTopic(number = 3, response = { request ->
            when {
                request.requestUrl!!.encodedPath == "/t/5/3.json" -> MockResponse().setBody("""{"id":5,"title":"深链","details":{"created_by":{"id":2,"username":"author"}},"post_stream":{"stream":[10,11,12],"posts":[{"id":12,"post_number":3,"user_id":3,"username":"reply"}]}}""")
                request.path!!.contains("/posts.json") -> MockResponse().setBody("""{"post_stream":{"posts":[$original]}}""")
                request.path!!.startsWith("/u/") -> MockResponse().setResponseCode(500).setBody("{}")
                else -> null
            }
        }) { vm, _ ->
            val loaded = vm.awaitState { !it.loading && it.detail?.originalPost != null && it.authorError != null }
            assertEquals(listOf(3), loaded.detail!!.posts.map { it.number })
            assertEquals(10, loaded.detail.originalPost!!.id)
            assertEquals("author", loaded.detail.author!!.username)
            assertNull("关注资料失败不影响已加载正文", loaded.error)
        }
    }

    @Test fun paginationCannotReplaceANewlyReactedPinnedOriginalWithAnOlderCopy() = runTest {
        val pages = AtomicInteger()
        val releasePage = CountDownLatch(1)
        withTopic(number = 3, response = { request ->
            when {
                request.requestUrl!!.encodedPath == "/t/5/3.json" -> MockResponse().setBody("""{"id":5,"details":{"created_by":{"id":2,"username":"author"}},"post_stream":{"stream":[10,11,12],"posts":[{"id":12,"post_number":3,"user_id":3,"username":"reply"}]}}""")
                request.path!!.endsWith("/toggle.json") -> MockResponse().setBody(postJson(27, true))
                request.path!!.contains("/posts.json") -> {
                    if (pages.incrementAndGet() > 1) releasePage.await(5, TimeUnit.SECONDS)
                    MockResponse().setBody("""{"post_stream":{"posts":[$original,{"id":11,"post_number":2,"user_id":3,"username":"reply"}]}}""")
                }
                else -> null
            }
        }) { vm, _ ->
            try {
                val post = vm.awaitState { it.detail?.originalPost != null }.detail!!.originalPost!!
                vm.previous()
                vm.like(post)
                assertEquals(27, vm.awaitState { it.busy.isEmpty() }.detail!!.originalPost!!.likes)
                releasePage.countDown()
                val after = vm.awaitState { !it.loadingPrevious }.detail!!
                assertEquals(27, after.originalPost!!.likes)
                assertEquals("heart", after.posts.first { it.number == 1 }.activeReaction)
            } finally { releasePage.countDown() }
        }
    }

    private suspend fun TestScope.withTopic(
        number: Int? = null,
        initial: String = topic,
        response: (RecordedRequest) -> MockResponse?,
        block: suspend (TopicViewModel, MockWebServer) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = response(request) ?: when {
                    request.requestUrl!!.encodedPath == "/t/5.json" -> MockResponse().setBody(initial)
                    request.path == "/session/csrf" -> MockResponse().setBody("""{"csrf":"test"}""")
                    request.path!!.startsWith("/u/") -> MockResponse().setBody("""{"user":{"id":2,"username":"author","can_follow":true,"is_followed":false}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val vm = TopicViewModel(CommunityRepository(DiscourseClient(NoCookies, server.url("/"),
                cooldown = com.kudo.android.network.RequestCooldown({ testScheduler.currentTime }))), 5, number, viewerId = 99)
            try { block(vm, server) } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }
}
