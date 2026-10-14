package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.topic.TopicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicEditRefreshTest {
    @Test fun editPatchesTargetAndTitleWithoutResettingLoadedWindowOrTarget() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/t/5/8.json") MockResponse().setBody("""{"id":5,"title":"旧标题","category_id":1,"post_stream":{"stream":[10,81,82],"posts":[{"id":10,"post_number":1,"user_id":2,"username":"me","cooked":"主帖"},{"id":81,"post_number":8,"user_id":2,"username":"me","cooked":"回复"},{"id":82,"post_number":9,"user_id":3,"username":"other","cooked":"下一楼"}]}}""") else MockResponse().setResponseCode(404)
            }
            server.start()
            val cookies = object : SessionCookies { override fun get(url: String) = ""; override fun set(url: String, cookie: String) {} }
            val vm = TopicViewModel(CommunityRepository(DiscourseClient(cookies, server.url("/"))), 5, 8)
            try {
                withContext(Dispatchers.IO) { withTimeout(5000) { while (vm.state.value.loading) delay(10) } }
                val before = vm.state.value
                assertEquals(3, before.detail!!.posts.size)
                val edited = before.detail.posts.first { it.id == 81 }.copy(cooked = "新回复", version = 2)
                vm.applyEdit(PostEditResult(5, edited, ""))
                assertEquals("新回复", vm.state.value.allPosts.first { it.id == 81 }.cooked)
                assertEquals("下一楼", vm.state.value.allPosts.first { it.id == 82 }.cooked)
                assertEquals(before.targetNumber, vm.state.value.targetNumber)
                assertEquals(before.targetRevision, vm.state.value.targetRevision)
                val original = before.detail.posts.first { it.id == 10 }.copy(cooked = "新主帖")
                vm.applyEdit(PostEditResult(5, original, "新标题"))
                assertEquals("新标题", vm.state.value.detail!!.title)
                assertEquals(3, vm.state.value.detail!!.posts.size)
                vm.applyEdit(PostEditResult(6, original, "无关标题"))
                assertEquals("新标题", vm.state.value.detail!!.title)
            } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
        }
    }
}
