package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FeedRepositoryTest {
    @Test fun generatedTagSlugWithoutExplicitIdStillUsesCanonicalIdPath() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.path == "/tag/444-tag/444.json") MockResponse().setBody("""{"tag":{"id":444,"name":"公告","slug":"444-tag"},"topic_list":{"topics":[{"id":31}]}}""")
                    else MockResponse().setResponseCode(404).setBody("{}")
            }
            val page = CommunityRepository(DiscourseClient(NoCookies, server.url("/"))).tagTopics("444-tag")
            assertEquals(31, page.topics.single().id)
            assertEquals(TopicTag("公告", "444-tag", id = 444), page.tag)
            assertEquals("/tag/444-tag/444.json", server.takeRequest().path)
        }
    }

    @Test fun everyCategoryFilterAndServerCursorKeepCategoryScope() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            for (filter in FeedFilter.entries) {
                val endpoint = if (filter == FeedFilter.UNREAD) "unseen" else filter.value
                val path = "/c/7/l/$endpoint.json"
                val cursor = "$path?page=1&order=activity"
                server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[{"id":22}],"more_topics_url":"$cursor"}}"""))
                val first = repository.topics(filter.value, categoryId = 7)
                assertEquals(path, server.takeRequest().path)
                server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[{"id":23}]}}"""))
                assertEquals(23, repository.topics(filter.value, categoryId = 7, next = first.next).topics.single().id)
                assertEquals(cursor, server.takeRequest().path)
            }
        }
    }

    @Test fun readingBadgesUseServerFieldsWithoutInferringUnreadFromFloorGaps() {
        val page = DiscourseParser.topicPage(org.json.JSONObject("""{"topic_list":{"topics":[
            {"id":1,"unseen":true},{"id":2,"unread_posts":12,"unseen":false},
            {"id":3,"last_read_post_number":1,"highest_post_number":500,"unread_posts":0},
            {"id":4},{"id":5,"unread_posts":-2}]}}"""))
        assertTrue(page.topics[0].unseen)
        assertEquals(listOf(0, 12, 0, 0, 0), page.topics.map(Topic::unreadPosts))
        assertFalse(page.topics[3].unseen)
    }

    @Test fun tagTopicsKeepTagScopeAndUseTheCanonicalIdPath() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[{"id":31}],"more_topics_url":"/tag/193-tag/193/l/hot.json?page=1"}}"""))
            val first = repository.tagTopics("193-tag", 193)
            assertEquals("/tag/193-tag/193.json", server.takeRequest().path)
            assertEquals(31, first.topics.single().id)
            server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[{"id":32}]}}"""))
            val next = repository.tagTopics("193-tag", 193, filter = "hot", next = first.next)
            assertEquals("/tag/193-tag/193/l/hot.json?page=1", server.takeRequest().path)
            assertEquals(32, next.topics.single().id)
        }
    }
    @Test fun homeFiltersMatchWebsiteAndKeepServerOrderAndPagination() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            for (filter in FeedFilter.entries) {
                val endpoint = if (filter == FeedFilter.UNREAD) "unseen" else filter.value
                server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[{"id":22,"title":"服务器首项","pinned":true},{"id":11,"title":"服务器次项"}],"more_topics_url":"/$endpoint.json?page=1&order=activity"}}"""))
                val page = repository.topics(filter.value)
                assertEquals("/$endpoint.json", server.takeRequest().path)
                assertEquals(listOf(22, 11), page.topics.map { it.id })
                server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[]}}"""))
                repository.topics(filter.value, next = page.next)
                assertEquals("/$endpoint.json?page=1&order=activity", server.takeRequest().path)
            }
            server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[]}}"""))
            repository.topics("unread", categoryId = 7)
            assertEquals("/c/7/l/unseen.json", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun newCountUsesTrackingStatesAndPreservesArrayRedirectsAndFailures() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            val user = Member(1, "a b", "测试账号", "")
            val states = """[
                {"topic_id":1,"last_read_post_number":null,"notification_level":2,"created_in_new_period":true},
                {"topic_id":1,"last_read_post_number":null,"notification_level":2,"created_in_new_period":true},
                {"topic_id":2,"last_read_post_number":null,"notification_level":null,"created_in_new_period":true},
                {"topic_id":3,"last_read_post_number":null,"notification_level":0,"created_in_new_period":true},
                {"topic_id":4,"last_read_post_number":null,"notification_level":1,"created_in_new_period":true},
                {"topic_id":5,"last_read_post_number":1,"highest_post_number":3,"notification_level":2},
                {"topic_id":6,"last_read_post_number":null,"created_in_new_period":false},
                {"topic_id":7,"last_read_post_number":null,"created_in_new_period":true,"deleted":true},
                {"topic_id":8,"last_read_post_number":null,"created_in_new_period":true,"is_seen":true}
            ]"""
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/tracking.json"))
            server.enqueue(MockResponse().setBody(states))
            assertEquals(2, repository.newTopicCount(user))
            assertEquals("/u/a%20b/topic-tracking-state.json", server.takeRequest().path)
            assertEquals("/tracking.json", server.takeRequest().path)
            server.enqueue(MockResponse().setBody(states))
            assertEquals(3, repository.newTopicCount(user.copy(unifiedNew = true)))
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            assertEquals(FailureKind.SIGN_IN, (runCatching { repository.newTopicCount(user) }.exceptionOrNull() as ApiFailure).kind)
            server.enqueue(MockResponse().setBody("{}"))
            assertTrue(runCatching { repository.newTopicCount(user) }.isFailure)
        } finally { server.shutdown() }
    }

    @Test fun personalListsUseTheirOwnEndpointsAndServerPagination() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            for (filter in listOf("posted", "read", "bookmarks")) {
                server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[{"id":12,"title":"个人话题"}],"more_topics_url":"/$filter.json?page=1"}}"""))
                val first = repository.topics(filter)
                assertEquals("/$filter.json", server.takeRequest().path)
                assertEquals(12, first.topics.single().id)
                server.enqueue(MockResponse().setBody("""{"topic_list":{"topics":[{"id":13,"title":"下一页"}]}}"""))
                assertEquals(13, repository.topics(filter, next = first.next).topics.single().id)
                assertEquals("/$filter.json?page=1", server.takeRequest().path)
            }
        } finally { server.shutdown() }
    }
}
