package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplyRulesTest {
    private val html = """<script id="data-preloaded" type="application/json">{"siteSettings":{"min_post_length":16,"min_personal_message_post_length":3,"max_post_length":64000}}</script>"""
    private fun site(override: Int = 0) = JSONObject("""{"categories":[{"id":2,"custom_fields":{"warden_min_post_length":$override}}]}""")

    private fun topicRulesServer(archetype: String?, categoryId: Int, settingsHtml: String = html) = MockWebServer().apply {
        val topic = JSONObject("""{"id":5,"title":"discbot","category_id":$categoryId,"post_stream":{"posts":[],"stream":[]}}""")
            .put("archetype", archetype)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/t/5.json" -> MockResponse().setBody(topic.toString())
                "/latest" -> MockResponse().setBody(settingsHtml)
                "/site.json" -> MockResponse().setBody(site(6).toString())
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    @Test fun categoryRulesOverrideTheSiteMinimumAndAreCappedByTheMaximum() {
        assertEquals(ReplyRules(16, 64000), parseReplyRules(html, site(), 2))
        assertEquals(ReplyRules(4, 64000), parseReplyRules(html, site(4), 2))
        assertEquals(ReplyRules(64000, 64000), parseReplyRules(html, site(99999), 2))
        assertTrue(runCatching { parseReplyRules(html, site(), 99) }.isFailure)
        assertTrue(runCatching { parseReplyRules("<html/>", site(), 2) }.isFailure)
    }

    @Test fun lengthMatchesTheWebWhitespaceCommentsQuotesAndUtf16Rules() {
        assertEquals(3, replyLength("  a\t\n b \u3000"))
        assertEquals(4, replyLength("短的回复<!--不计字数-->"))
        assertEquals(2, replyLength("[quote=\"someone, post:2\"]引用不计字数[/quote]回答"))
        assertEquals(2, replyLength("[quote=outer]a[quote=inner]b[/quote]c[/quote]回答"))
        assertEquals(2, replyLength("😀"))
        assertNotNull(ReplyRules(16, 20).problem("a".repeat(15)))
        assertNull(ReplyRules(16, 20).problem("a".repeat(16)))
        assertNotNull(ReplyRules(16, 20).problem("a".repeat(21)))
    }

    @Test fun personalMessageRulesUsePersonalMinimumAndSiteMaximum() {
        val messageHtml = """<script id="data-preloaded" type="application/json">{"siteSettings":{"min_personal_message_post_length":3,"max_post_length":500}}</script>"""
        assertEquals(PersonalMessageRules(3, 500), parsePersonalMessageRules(messageHtml))
        assertTrue(runCatching { parsePersonalMessageRules("<html/>") }.isFailure)
        assertNotNull(PersonalMessageRules(3, 5).problem("123456"))
        assertNull(PersonalMessageRules(3, 5).problem("123"))
    }

    @Test fun knownCategoryUsesOnlyReadonlySiteConfigurationRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(html))
            server.enqueue(MockResponse().setBody(site(6).toString()))
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            assertEquals(ReplyRules(6, 64000), repository.replyRules(5, 2))
            assertEquals("/latest", server.takeRequest().path)
            val second = server.takeRequest()
            assertEquals("GET", second.method); assertEquals("/site.json", second.path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun privateMessageRepliesWithoutACategoryUseThePrivateMinimumAndSkipSiteCategories() = runBlocking {
        listOf(null, 0).forEach { categoryId ->
            topicRulesServer("private_message", 0).use { server ->
                val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
                val rules = repository.replyRules(5, categoryId)
                assertEquals(ReplyRules(3, 64000), rules)
                assertNotNull(rules.problem("回答"))
                assertNull(rules.problem("回答了"))
                assertEquals(2, server.requestCount)
                assertEquals("/t/5.json", server.takeRequest().path)
                val settings = server.takeRequest()
                assertEquals("GET", settings.method)
                assertEquals("/latest", settings.path)
            }
        }
    }

    @Test fun absentCategoryDoesNotMakeAnOrdinaryOrUnidentifiedTopicAPrivateMessage() = runBlocking {
        listOf("regular", null).forEach { archetype ->
            topicRulesServer(archetype, 0).use { server ->
                val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
                val failure = runCatching { repository.replyRules(5, 0) }.exceptionOrNull()
                assertTrue(failure is ApiFailure)
                assertEquals("暂时无法读取当前分类回帖规则", failure?.message)
            }
        }
    }

    @Test fun unknownOrdinaryCategoryUsesTopicMetadataAndItsCategoryOverride() = runBlocking {
        listOf(null, 0).forEach { categoryId ->
            topicRulesServer("regular", 2).use { server ->
                val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
                assertEquals(ReplyRules(6, 64000), repository.replyRules(5, categoryId))
                assertEquals(3, server.requestCount)
                assertEquals("/t/5.json", server.takeRequest().path)
                assertEquals("/latest", server.takeRequest().path)
                assertEquals("/site.json", server.takeRequest().path)
            }
        }
    }

    @Test fun missingPrivateMessageRulesFailInsteadOfUsingTheOrdinaryMinimum() = runBlocking {
        val missingRules = html.replace("\"min_personal_message_post_length\":3,", "")
        topicRulesServer("private_message", 0, missingRules).use { server ->
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            assertTrue(runCatching { repository.replyRules(5, 0) }.isFailure)
            assertEquals(2, server.requestCount)
            assertEquals("/t/5.json", server.takeRequest().path)
            assertEquals("/latest", server.takeRequest().path)
        }
    }
}
