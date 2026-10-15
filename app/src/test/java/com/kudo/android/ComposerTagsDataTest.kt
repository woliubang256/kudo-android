package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComposerTagsDataTest {
    private val cookies = object : SessionCookies {
        override fun get(url: String) = ""
        override fun set(url: String, cookie: String) {}
    }

    @Test fun searchUsesInputCategoryAndAllSelectedIdsWithoutHardcodedLimitAndExcludesForbiddenTags() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[{"id":444,"text":"公告","slug":"444-tag"},{"id":444,"name":"公告"},{"id":9,"name":"受限","disabled":true}]}"""))
            val tags = CommunityRepository(DiscourseClient(cookies, server.url("/"))).composerTags(" 公 ", 4,
                listOf(TopicTag("开发", id = 1), TopicTag("分享", id = 2)))
            assertEquals(listOf(TopicTag("公告", "444-tag", id = 444)), tags)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/tags/filter/search", url.encodedPath)
            assertEquals("公", url.queryParameter("q"))
            assertEquals("4", url.queryParameter("categoryId"))
            assertEquals("true", url.queryParameter("filterForInput"))
            assertEquals(listOf("1", "2"), url.queryParameterValues("selected_tag_ids[]"))
            assertNull(url.queryParameter("limit"))
            assertNull(url.queryParameter("category_id"))
        }
    }

    @Test fun restoredTagNamesUseCompleteNameFallbackRatherThanPartialIdSelection() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[]}"""))
            CommunityRepository(DiscourseClient(cookies, server.url("/"))).composerTags("", 4,
                listOf(TopicTag("公告"), TopicTag("开发", id = 2)))
            val url = server.takeRequest().requestUrl!!
            assertEquals(listOf("公告", "开发"), url.queryParameterValues("selected_tags[]"))
            assertNull(url.queryParameter("selected_tag_ids[]"))
        }
    }

    @Test fun topicSubmitsRepeatedTagNamesButReplyDoesNotSubmitTopicMetadata() = runBlocking {
        MockWebServer().use { server ->
            val repository = CommunityRepository(DiscourseClient(cookies, server.url("/")))
            server.enqueue(MockResponse().setBody("""{"csrf":"fixture-token"}"""))
            repeat(2) { server.enqueue(MockResponse().setBody("""{"id":123,"topic_id":456}""")) }
            assertEquals(PublishResult(456, false), repository.publish("正文", "标题", 4, null, null, listOf("公告", "开发", "公告", " ")))
            assertEquals("/session/csrf", server.takeRequest().path)
            val post = server.takeRequest()
            assertEquals("/posts.json", post.path)
            val body = "https://fixture.invalid/?${post.body.readUtf8()}".toHttpUrl()
            assertEquals(listOf("公告", "开发"), body.queryParameterValues("tags[]"))
            assertEquals("4", body.queryParameter("category"))
            assertEquals("标题", body.queryParameter("title"))
            assertEquals("正文", body.queryParameter("raw"))
            assertEquals("fixture-token", post.getHeader("X-CSRF-Token"))
            repository.publish("回复正文", "不能提交的标题", 4, 456, 8, listOf("公告"))
            val reply = "https://fixture.invalid/?${server.takeRequest().body.readUtf8()}".toHttpUrl()
            assertNull(reply.queryParameter("tags[]"))
            assertNull(reply.queryParameter("title"))
            assertNull(reply.queryParameter("category"))
            assertEquals("456", reply.queryParameter("topic_id"))
            assertEquals("8", reply.queryParameter("reply_to_post_number"))
        }
    }

    @Test fun draftTagArraysPreserveNamesAndOrderAndOldOrMalformedDraftsRemainReadable() {
        assertEquals(emptyList<String>(), decodeDraftTags(null))
        assertEquals(emptyList<String>(), decodeDraftTags("invalid"))
        assertEquals(listOf("公告", "逗,号", "带\"引号"), decodeDraftTags("""["公告","逗,号","带\"引号","公告",""]"""))
        val drafts = localAccountDrafts(mapOf("draft_7_new_4_raw" to "正文", "draft_7_new_4_tags" to """["公告","开发"]""",
            "draft_7_cloud_new_topic_raw" to "备份", "draft_7_cloud_new_topic_tags" to """["云端"]"""), 7)
        assertEquals(listOf("公告", "开发"), drafts.first { !it.remote }.draft.tags)
        assertEquals(listOf("云端"), drafts.first { it.remote }.draft.tags)
        assertEquals(listOf("原标签"), parseCloudDraft(JSONObject("""{"draft_key":"new_topic","data":{"reply":"正文","tags":["原标签"]}}""")).draft.tags)
    }
}
