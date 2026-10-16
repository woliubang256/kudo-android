package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class TopicPrivateMessageTest {
    private val cookies = object : SessionCookies { override fun get(url: String) = ""; override fun set(url: String, cookie: String) {} }
    private val viewer = Member(1, "me", "", "")
    private val post = Post(81, 8, Member(2, "reply_author", "", ""), "<p>正文</p>", "", 0, false, null, null, false)
    private suspend fun await(predicate: () -> Boolean) = withContext(Dispatchers.IO) { withTimeout(5000) { while (!predicate()) delay(10) } }
    private fun detail(title: String = "标题 & 中文？") = """{"id":5,"title":"$title","post_stream":{"posts":[{"id":10,"post_number":1,"username":"topic_author"}],"stream":[10]}}"""

    @Test fun replyMessageTargetsItsAuthorAndExactFloorWithoutSendingAnything() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            val requests = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("GET", request.method)
                    requests += request.path!!
                    return MockResponse().setBody(if (request.path == "/u/reply_author.json")
                        """{"user":{"id":2,"username":"reply_author","can_send_private_message_to_user":true}}""" else detail())
                }
            }
            server.start()
            val vm = TopicActionsViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), viewer)
            val events = CopyOnWriteArrayList<TopicActionEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            try {
                vm.open(5, true, post, isReply = true)
                assertEquals(0, server.requestCount)
                vm.privateMessage(); vm.privateMessage()
                await { vm.state.topicId == null }
                val event = events.single() as TopicActionEvent.ComposePrivateMessage
                assertEquals("reply_author", event.recipient)
                assertEquals(PostMessageSource(5, 8, "标题 & 中文？"), event.source)
                assertEquals(listOf("/u/reply_author.json", "/t/5/1.json"), requests)
            } finally { collector.cancelAndJoin(); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }

    @Test fun mainPostMessageReusesAlreadyLoadedTitleAndTargetsFloorOne() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(detail()))
            server.enqueue(MockResponse().setBody("""{"user":{"id":2,"username":"topic_author","can_send_private_message_to_user":true}}"""))
            val vm = TopicActionsViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), viewer)
            val events = CopyOnWriteArrayList<TopicActionEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            try {
                vm.open(5); await { !vm.state.loading }
                vm.privateMessage(); await { vm.state.topicId == null }
                val event = events.single() as TopicActionEvent.ComposePrivateMessage
                assertEquals("topic_author", event.recipient)
                assertEquals(1, event.source.postNumber)
                assertEquals(2, server.requestCount)
                assertEquals("/t/5/1.json", server.takeRequest().path)
                assertEquals("/u/topic_author.json", server.takeRequest().path)
            } finally { collector.cancelAndJoin(); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }

    @Test fun permissionFailureKeepsTheTargetAndRetryChecksPermissionAgain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            val allowed = AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (request.path!!.startsWith("/u/"))
                    """{"user":{"id":2,"username":"reply_author","can_send_private_message_to_user":${allowed.get()}}}""" else detail())
            }
            server.start()
            val vm = TopicActionsViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), viewer)
            val events = CopyOnWriteArrayList<TopicActionEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            try {
                vm.open(5, true, post, isReply = true); vm.privateMessage(); await { !vm.state.loading }
                assertEquals(81, vm.state.post!!.id)
                assertNotNull(vm.state.error)
                assertTrue(vm.state.composingMessage)
                assertTrue(events.isEmpty())
                assertEquals(1, server.requestCount)
                allowed.set(true); vm.privateMessage(); await { vm.state.topicId == null }
                assertEquals("reply_author", (events.single() as TopicActionEvent.ComposePrivateMessage).recipient)
                assertEquals(3, server.requestCount)
            } finally { collector.cancelAndJoin(); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }

    @Test fun delayedPreparationCannotOpenAnEditorAfterSwitchingFloorOrDismissing() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            server.start()
            val vm = TopicActionsViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), viewer)
            val events = CopyOnWriteArrayList<TopicActionEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            try {
                for (dismiss in listOf(false, true)) {
                    val before = server.requestCount
                    server.enqueue(MockResponse().setBody("""{"user":{"can_send_private_message_to_user":true}}""").setBodyDelay(200, TimeUnit.MILLISECONDS))
                    vm.open(5, true, post, isReply = true); vm.privateMessage()
                    await { server.requestCount > before }
                    if (dismiss) vm.dismiss() else vm.open(6, true, post.copy(id = 90, number = 9), isReply = true)
                    withContext(Dispatchers.IO) { delay(300) }
                    assertTrue(events.isEmpty())
                    assertEquals(if (dismiss) null else 6, vm.state.topicId)
                    assertFalse(vm.state.loading)
                }
            } finally { collector.cancelAndJoin(); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }

    @Test fun missingTitleKeepsTheReplyAndRetryNeverFallsBackToAnEmptyMessage() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            val missing = AtomicBoolean(true)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (request.path!!.startsWith("/u/"))
                    """{"user":{"can_send_private_message_to_user":true}}""" else detail(if (missing.get()) "" else "恢复的标题"))
            }
            server.start()
            val vm = TopicActionsViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), viewer)
            val events = CopyOnWriteArrayList<TopicActionEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
            try {
                vm.open(5, true, post, isReply = true); vm.privateMessage(); await { !vm.state.loading }
                assertEquals("暂时无法读取话题标题", vm.state.error?.message)
                assertEquals(81, vm.state.post!!.id)
                assertTrue(events.isEmpty())
                missing.set(false); vm.privateMessage(); await { vm.state.topicId == null }
                assertEquals("回复：恢复的标题", (events.single() as TopicActionEvent.ComposePrivateMessage).source.title)
            } finally { collector.cancelAndJoin(); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }

    @Test fun bufferedNavigationFromAClosedMenuCannotReplayForANewTarget() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (request.path!!.startsWith("/u/"))
                    """{"user":{"can_send_private_message_to_user":true}}""" else detail())
            }
            server.start()
            val vm = TopicActionsViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), viewer)
            try {
                vm.open(5, true, post, isReply = true); vm.privateMessage(); await { vm.state.topicId == null }
                vm.dismiss()
                vm.open(5, true, post.copy(id = 82, number = 9), isReply = true)
                vm.privateMessage(); await { vm.state.topicId == null }
                val event = vm.events.first() as TopicActionEvent.ComposePrivateMessage
                assertEquals(9, event.source.postNumber)
            } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }

    @Test fun guestAndOwnPostCannotPrepareAMessage() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            server.start()
            val repo = CommunityRepository(DiscourseClient(cookies, server.url("/")))
            for (member in listOf(null, post.author)) {
                val vm = TopicActionsViewModel(repo, member)
                try { vm.open(5, true, post, isReply = true); vm.privateMessage(); assertEquals(0, server.requestCount) }
                finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            }
            Dispatchers.resetMain()
        }
    }
}
