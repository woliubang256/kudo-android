package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReactionHistoryTest {
    private val post = DiscourseParser.post(JSONObject("""{"id":42,"post_number":1,"reaction_users_count":100,
        "reactions":[{"id":"heart","type":"emoji","count":40},{"id":"laughing","type":"emoji","count":30},
        {"id":"+1","type":"emoji","count":20},{"id":"clap","type":"emoji","count":10}]}"""))

    @Test fun popularSummaryUsesThreeStableIconsWithoutLosingServerTotal() {
        assertEquals(listOf("heart", "laughing", "+1"), post.popularReactions().map { it.id })
        assertEquals(100, post.reactionCount)
        assertEquals(listOf("heart"), post.copy(reactionsAvailable = false, likes = 12).popularReactions().map { it.id })
        assertTrue(post.copy(reactions = emptyList(), reactionCount = 0).popularReactions().isEmpty())
        assertEquals(listOf("a", "b"), post.copy(reactions = listOf(PostReaction("a", 2), PostReaction("b", 2),
            PostReaction("a", 2), PostReaction("empty", 0))).popularReactions().map { it.id })
    }

    @Test fun groupedHistoryKeepsCountsAndUsernamesWhenNumericIdsAreMissing() {
        val result = parseReactionHistory(JSONObject("""{"reaction_users":[
            {"id":"heart","count":80,"users":[{"username":"Alice","name":"甲","created_at":"2026-10-18T01:00:00Z"},{"username":"alice"}]},
            {"id":"laughing","count":20,"users":[{"username":"Bob","created_at":"2026-10-18T02:00:00Z"},{"username":"","id":0}]}]}"""))
        assertEquals(100, result.total)
        assertEquals(listOf("Bob", "Alice"), result.users(null).map { it.member.username })
        assertEquals(listOf("Alice"), result.users("heart").map { it.member.username })
        assertEquals(80, result.groups.first().count)
        assertTrue(result.partial)
        assertNull(result.nextPage)
    }

    @Test fun pluginRequestsAreReadOnlyAndPermissionErrorsAreNotFallbackLikes() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            server.enqueue(MockResponse().setBody("""{"reaction_users":[]}"""))
            assertTrue(repo.reactionHistory(post).groups.isEmpty())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/discourse-reactions/posts/42/reactions-users.json", request.path)
            server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
            try { repo.reactionHistory(post); fail("权限错误不能伪装为空名单") }
            catch (failure: ApiFailure) { assertEquals(FailureKind.FORBIDDEN, failure.kind) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun coreLikesUseRealIdAndPageParametersAndMergeWithoutDuplicates() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repo = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            val users = (1..50).joinToString(",") { """{"id":$it,"username":"user$it"}""" }
            server.enqueue(MockResponse().setBody("""{"post_action_users":[$users],"total_rows_post_action_users":51}"""))
            val first = repo.reactionHistory(post.copy(reactionsAvailable = false), 0)
            assertEquals(1, first.nextPage)
            val query = server.takeRequest().requestUrl!!
            assertEquals("42", query.queryParameter("id"))
            assertEquals("2", query.queryParameter("post_action_type_id"))
            assertEquals("0", query.queryParameter("page"))
            assertEquals("50", query.queryParameter("limit"))
            server.enqueue(MockResponse().setBody("""{"post_action_users":[{"username":"user50"},{"username":"user51"}],"total_rows_post_action_users":51}"""))
            val merged = first.append(repo.reactionHistory(post.copy(reactionsAvailable = false), 1))
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("page"))
            assertEquals(51, merged.users(null).size)
            assertEquals(51, merged.total)
            assertFalse(merged.partial)
            assertNull(merged.nextPage)
        }
    }

    @Test fun malformedHistoryIsAnErrorRatherThanAFalseEmptyResult() {
        try { parseReactionHistory(JSONObject("{}")); fail("缺失封套需显示错误") }
        catch (failure: ApiFailure) { assertEquals(FailureKind.SERVER, failure.kind) }
    }
}
