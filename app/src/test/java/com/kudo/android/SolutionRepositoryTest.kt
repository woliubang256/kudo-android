package com.kudo.android

import com.kudo.android.data.CommunityRepository
import com.kudo.android.data.DiscourseClient
import com.kudo.android.data.Post
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolutionRepositoryTest {
    @Test fun acceptingAndUnacceptingSolutionsUseThePluginEndpointsAndRefreshThePost() = runBlocking {
        MockWebServer().use { server ->
            var accepted = false
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/session/csrf" -> MockResponse().setBody("""{"csrf":"test-token"}""")
                    request.path == "/solution/accept" -> {
                        assertEquals("POST", request.method)
                        assertTrue(request.body.readUtf8().contains("id=7"))
                        accepted = true
                        MockResponse().setBody("""{"success":"OK"}""")
                    }
                    request.path == "/solution/unaccept" -> {
                        assertEquals("POST", request.method)
                        assertTrue(request.body.readUtf8().contains("id=7"))
                        accepted = false
                        MockResponse().setBody("""{"success":"OK"}""")
                    }
                    request.path == "/posts/7.json" -> MockResponse().setBody(postJson(accepted))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            val initial = Post(7, 2, com.kudo.android.data.Member(3, "helper", "", ""), "<p>回复</p>", "", 0,
                false, null, null, true, canAcceptSolution = true)

            val selected = repository.setSolution(initial, true)
            assertTrue(selected.isSolution)
            assertFalse(selected.canAcceptSolution)
            assertTrue(selected.canUnacceptSolution)

            val cleared = repository.setSolution(selected, false)
            assertFalse(cleared.isSolution)
            assertTrue(cleared.canAcceptSolution)
            assertFalse(cleared.canUnacceptSolution)
        }
    }

    private fun postJson(accepted: Boolean) = """{"id":7,"post_number":2,"user_id":3,"username":"helper","cooked":"<p>回复</p>","accepted_answer":$accepted,"can_accept_answer":${!accepted},"can_unaccept_answer":$accepted}"""
}
