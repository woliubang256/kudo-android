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

class SearchDataTest {
    @Test fun postsKeepServerOrderAndMultipleMatchingFloorsWithRealAuthorIds() {
        val result = parseSearchPosts(JSONObject("""{"topics":[{"id":3,"title":"主题 &amp; 标题","tags":[{"name":"纯水","slug":"1461-tag"}]}],"posts":[{"id":32,"topic_id":3,"post_number":9,"user_id":8,"blurb":"<span class='search-highlight'>命中</span>摘要"},{"id":31,"topic_id":3,"post_number":2,"user_id":7}],"grouped_search_result":{"more_full_page_results":true}}"""), 1)
        assertEquals(listOf(32, 31), result.posts.map { it.postId })
        assertEquals(listOf(9, 2), result.posts.map { it.postNumber })
        assertEquals(8, result.posts.first().topic.author!!.id)
        assertEquals("命中摘要", result.posts.first().topic.excerpt)
        assertEquals("主题 & 标题", result.posts.first().topic.title)
        assertEquals("1461-tag", result.posts.first().topic.tagDetails.single().slug)
        assertTrue(result.hasMore)
    }

    @Test fun lastPageAndMissingTopicsDoNotProduceFalsePaginationOrBrokenHits() {
        val json = JSONObject("""{"posts":[{"id":1,"topic_id":3}],"grouped_search_result":{"more_full_page_results":true}}""")
        assertTrue(parseSearchPosts(json, 10).posts.isEmpty())
        assertFalse(parseSearchPosts(json, 10).hasMore)
    }

    @Test fun guestNeverRequestsRecentSearchesAndHistoryUsesStrictStrings() = runBlocking {
        MockWebServer().use { server ->
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            assertTrue(repository.recentSearches(null).isEmpty())
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"recent_searches":["linux","linux",null,{},3,"  ","android"]}"""))
            assertEquals(listOf("linux", "android"), repository.recentSearches(5))
            assertEquals("/u/recent-searches.json", server.takeRequest().path)
        }
    }

    @Test fun userSearchUsesWebsiteEndpointWithoutGroupsOrPostFilters() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"users":[{"id":7,"username":"neo","name":"昵称"}],"groups":[{"name":"staff"}]}"""))
            val result = CommunityRepository(DiscourseClient(NoCookies, server.url("/"))).searchResults("@neo", SearchKind.USERS)
            assertEquals("neo", result.users.single().username)
            val request = server.takeRequest().requestUrl!!
            assertEquals("/u/search/users.json", request.encodedPath)
            assertEquals("neo", request.queryParameter("term"))
            assertEquals("20", request.queryParameter("limit"))
            assertEquals("false", request.queryParameter("include_groups"))
            assertFalse(result.hasMore)
        }
    }

    @Test fun clearingRecentSearchesUsesTheWebsiteDeleteWithCsrfAndGuestsNeverWrite() = runBlocking {
        MockWebServer().use { server ->
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            repository.clearRecentSearches(null)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"csrf":"test-token"}"""))
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            repository.clearRecentSearches(7)
            assertEquals("/session/csrf", server.takeRequest().path)
            val cleared = server.takeRequest()
            assertEquals("DELETE", cleared.method)
            assertEquals("/u/recent-searches.json", cleared.path)
            assertEquals("test-token", cleared.getHeader("X-CSRF-Token"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun categoryAndTagSearchKeepsSlugAndDoesNotInventMorePages() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/site.json" -> MockResponse().setBody("""{"categories":[{"id":4,"name":"开发调优","slug":"dev"},{"id":5,"name":"资源","slug":"resource"}]}""")
                    "/tags/filter/search" -> {
                        val limit = request.requestUrl!!.queryParameter("limit")?.toIntOrNull()
                        if (limit != null && limit !in 0..5) MockResponse().setResponseCode(400).setBody("""{"errors":["limit 无效"]}""")
                        else MockResponse().setBody("""{"results":[{"text":"开发","slug":"123-tag"},null,{"text":3}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val result = CommunityRepository(DiscourseClient(NoCookies, server.url("/"))).searchResults("开发", SearchKind.CATEGORIES_TAGS)
            assertEquals(4, result.categories.single().id)
            assertEquals("123-tag", result.tags.single().slug)
            assertFalse(result.hasMore)
            assertEquals("/site.json", server.takeRequest().requestUrl!!.encodedPath)
            val tags = server.takeRequest().requestUrl!!
            assertEquals("/tags/filter/search", tags.encodedPath)
            assertEquals("开发", tags.queryParameter("q"))
            assertNull(tags.queryParameter("limit"))
        }
    }

    @Test fun tagSuggestionsUseServerDefaultEvenWhenSiteLimitIsOne() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val limit = request.requestUrl!!.queryParameter("limit")?.toIntOrNull()
                    return if (limit != null && limit !in 0..1) MockResponse().setResponseCode(400).setBody("""{"errors":["limit 无效"]}""")
                    else MockResponse().setBody("""{"results":[{"text":"安卓开发","slug":"android-dev"}]}""")
                }
            }
            val tags = CommunityRepository(DiscourseClient(NoCookies, server.url("/"))).searchTags("安卓 开发")
            assertEquals("安卓开发", tags.single().name)
            assertEquals("android-dev", tags.single().slug)
            val request = server.takeRequest().requestUrl!!
            assertEquals("/tags/filter/search", request.encodedPath)
            assertEquals("安卓 开发", request.queryParameter("q"))
            assertNull(request.queryParameter("limit"))
        }
    }

    @Test fun pluginOptionsFollowSiteFlagsAndMatchingAccountPermission() {
        val preload = JSONObject().put("siteSettings", JSONObject().put("enable_category_experts", true)
            .put("show_category_expert_advanced_search_filters", true).put("solved_enabled", true))
            .put("currentUser", JSONObject().put("id", 7).put("can_assign_globally", true))
        val html = "<div data-preloaded='${preload}'></div>"
        assertTrue(parseSearchCapabilities(html, 7).assignTo)
        assertFalse(parseSearchCapabilities(html, 8).assignTo)
        assertFalse(parseSearchCapabilities(html, null).assignTo)
        assertTrue(parseSearchCapabilities(html, null).expertResponse)
        assertTrue(parseSearchCapabilities(html, null).solved)
        assertFalse(SearchSort.available(false).contains(SearchSort.READ))
    }
}
