package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostEditingContractTest {
    private val cookies = object : SessionCookies { override fun get(url: String) = ""; override fun set(url: String, cookie: String) {} }
    private val postJson = """{"id":81,"post_number":8,"user_id":2,"username":"me","topic_id":5,"raw":"原文 ![图](upload://a.png)","can_edit":true}"""
    private val post = DiscourseParser.post(JSONObject(postJson))
    @Test fun editRequiresBothOwnIdentityAndServerPermission() {
        assertTrue(post.editableBy(2))
        assertFalse(post.editableBy(null))
        assertFalse(post.editableBy(3))
        assertFalse(post.copy(canEdit = false).editableBy(2))
    }
    @Test fun loadsExactRawOnlyForEditableOwner() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody(postJson))
            val repo = CommunityRepository(DiscourseClient(cookies, server.url("/")))
            val source = repo.editablePost(81, 2)
            assertEquals("/posts/81.json", server.takeRequest().path)
            assertEquals("原文 ![图](upload://a.png)", source.raw)
            server.enqueue(MockResponse().setBody(postJson))
            assertTrue(runCatching { repo.editablePost(81, 3) }.exceptionOrNull() is ApiFailure)
            server.enqueue(MockResponse().setBody(JSONObject(postJson).apply { remove("raw") }.toString()))
            assertEquals("未能读取编辑原文，请重试", runCatching { repo.editablePost(81, 2) }.exceptionOrNull()?.message)
        }
    }
    @Test fun savesOnePutWithOriginalTextAndNoReplyOrCategoryChanges() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"test"}"""))
            server.enqueue(MockResponse().setBody(JSONObject().put("post", JSONObject(postJson)).toString()))
            val repo = CommunityRepository(DiscourseClient(cookies, server.url("/")))
            val source = EditablePost(post, 5, "old & body", "")
            assertEquals(81, repo.editPost(source, 2, " new & body ", "ignored", " 修正 ").id)
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals("PUT", request.method); assertEquals("/posts/81.json", request.path)
            assertEquals("post%5Braw%5D=%20new%20%26%20body%20&post%5Boriginal_text%5D=old%20%26%20body&post%5Bedit_reason%5D=%E4%BF%AE%E6%AD%A3", request.body.readUtf8())
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun firstPostUsesTopLevelTitleAndRejectsMismatchedResponse() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"csrf":"test"}"""))
            server.enqueue(MockResponse().setBody(JSONObject().put("post", JSONObject(postJson)).toString()))
            val source = EditablePost(post.copy(number = 1), 5, "old", "旧标题")
            val repo = CommunityRepository(DiscourseClient(cookies, server.url("/")))
            assertTrue(runCatching { repo.editPost(source, 2, "new", "新标题", "") }.isFailure)
            server.takeRequest()
            assertTrue(server.takeRequest().body.readUtf8().endsWith("&title=%E6%96%B0%E6%A0%87%E9%A2%98"))
            assertTrue(runCatching { repo.editPost(source, 3, "new", "新标题", "") }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun conflictDoesNotRetryTheWrite() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("""{"csrf":"test"}"""))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"errors":["内容已被修改"]}"""))
            val repo = CommunityRepository(DiscourseClient(cookies, server.url("/")))
            assertTrue(runCatching { repo.editPost(EditablePost(post, 5, "old", ""), 2, "new", "", "") }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }
}
