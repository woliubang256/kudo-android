package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostBoostsTest {
    private val post = Post(12, 8, Member(2, "user", "用户", ""), "<p>正文</p>", "", 2, false, null, 2, true, canBoost = true)
    private val json = """{"id":8,"cooked":"<p>加油<img class=\"emoji\" alt=\":grin:\" src=\"/grin.png\"></p>","user":{"id":3,"username":"booster","avatar_template":"/{size}.png"}}"""
    private val boostedPost get() = post.copy(boosts = DiscourseParser.post(JSONObject("""{"id":12,"boosts":[$json]}""")).boosts)

    private fun reactionJson(reaction: String?) = JSONObject("""{"id":12,"post_number":8,"user_id":2,"username":"user",
        "cooked":"<p>正文</p>","actions_summary":[{"id":2,"count":27,"acted":${reaction == "heart"},"can_act":true,"can_undo":true}],
        "reactions":[{"id":"heart","type":"emoji","count":27}],"reaction_users_count":29,
        "current_user_reaction":${if (reaction == null) "null" else """{"id":"$reaction","type":"emoji","can_undo":true}"""}}""")

    @Test fun likingUnlikingAndChangingReactionPreserveOmittedBoostFields() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"token"}"""))
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            var current = boostedPost.copy(reactionsAvailable = true)
            for (reaction in listOf("heart", null, "clap")) {
                server.enqueue(MockResponse().setBody(reactionJson(reaction).toString()))
                current = if (reaction == "clap") repository.react(current, reaction) else repository.like(current)
                assertEquals(reaction, current.activeReaction)
                assertEquals(29, current.reactionCount)
                assertEquals(boostedPost.boosts, current.boosts)
                assertTrue(current.canBoost)
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun coreLikeAndUnlikePreserveBoostsWithoutTheReactionsPlugin() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"token"}"""))
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            var current = boostedPost
            for (reaction in listOf("heart", null)) {
                val response = reactionJson(reaction).apply {
                    remove("reactions"); remove("current_user_reaction"); remove("reaction_users_count")
                }
                server.enqueue(MockResponse().setBody(response.toString()))
                current = repository.like(current)
                assertEquals(reaction, current.activeReaction)
                assertEquals(boostedPost.boosts, current.boosts)
                assertTrue(current.canBoost)
            }
            assertEquals("/session/csrf", server.takeRequest().path)
            val like = server.takeRequest()
            assertEquals("POST", like.method); assertEquals("/post_actions", like.path)
            val unlike = server.takeRequest()
            assertEquals("DELETE", unlike.method); assertEquals("/post_actions/12?post_action_type_id=2", unlike.path)
        }
    }

    @Test fun reactionRefetchPreservesOmittedBoostFields() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"token"}"""))
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody(reactionJson("heart").toString()))
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            val updated = repository.like(boostedPost.copy(reactionsAvailable = true))
            assertEquals("heart", updated.activeReaction)
            assertEquals(boostedPost.boosts, updated.boosts)
            assertTrue(updated.canBoost)
            server.takeRequest(); server.takeRequest()
            assertEquals("/posts/12.json", server.takeRequest().path)
        }
    }

    @Test fun explicitBoostFieldsOverridePreviousValuesIndependently() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"token"}"""))
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            val initial = boostedPost.copy(reactionsAvailable = true)
            server.enqueue(MockResponse().setBody(reactionJson("heart").put("boosts", org.json.JSONArray()).toString()))
            val cleared = repository.like(initial)
            assertTrue(cleared.boosts.isEmpty()); assertTrue(cleared.canBoost)
            server.enqueue(MockResponse().setBody(reactionJson("heart").put("can_boost", false).toString()))
            val forbidden = repository.like(initial)
            assertEquals(initial.boosts, forbidden.boosts); assertFalse(forbidden.canBoost)
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody(reactionJson("heart")
                .put("boosts", org.json.JSONArray()).put("can_boost", false).toString()))
            val refreshed = repository.like(initial)
            assertTrue(refreshed.boosts.isEmpty()); assertFalse(refreshed.canBoost)
        }
    }

    @Test fun aReactionCannotEnableBoostWhenThePermissionWasMissing() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"token"}"""))
            server.enqueue(MockResponse().setBody(reactionJson("heart").toString()))
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            val updated = repository.like(boostedPost.copy(canBoost = false, reactionsAvailable = true))
            assertEquals(boostedPost.boosts, updated.boosts)
            assertFalse(updated.canBoost)
        }
    }

    @Test fun boostAfterLikingPreservesTheConfirmedReaction() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"token"}"""))
            server.enqueue(MockResponse().setBody(reactionJson("heart").toString()))
            server.enqueue(MockResponse().setBody(JSONObject(json).put("id", 9).toString()))
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            val liked = repository.like(boostedPost.copy(reactionsAvailable = true))
            val boosted = repository.boost(liked, "加油", emptySet())
            assertEquals(liked.copy(boosts = boosted.boosts), boosted)
            assertEquals(listOf(8, 9), boosted.boosts.map(PostBoost::id))
            assertEquals("heart", boosted.activeReaction)
            assertEquals(29, boosted.reactionCount)
        }
    }

    @Test fun countsWebsiteEmojiSequencesAndUtf16InsteadOfAllGraphemes() {
        assertEquals(BoostCount(2, 0), BoostRules.count(" 中文 ", emptySet()))
        listOf("😀", "🇨🇳", "👩‍💻", "👍🏽", "❤️‍🔥", "1️⃣").forEach {
            assertEquals(it, BoostCount(1, 1), BoostRules.count(it, emptySet()))
        }
        assertEquals(BoostCount(2, 0), BoostRules.count("𝕏", emptySet()))
        assertEquals(BoostCount(3, 3), BoostRules.count(":Grin::smile::custom:", setOf("custom")))
        assertEquals(BoostCount(10, 0), BoostRules.count(":not_real:", emptySet()))
        assertNull(BoostRules.count("字".repeat(16), emptySet()).problem)
        assertNotNull(BoostRules.count("字".repeat(17), emptySet()).problem)
        assertNull(BoostRules.count("😀".repeat(5), emptySet()).problem)
        assertNotNull(BoostRules.count("😀".repeat(6), emptySet()).problem)
        assertNotNull(BoostRules.count(" \n ", emptySet()).problem)
    }

    @Test fun parsesBoostsAndPostsWithoutBoostFields() {
        val parsed = DiscourseParser.post(JSONObject("""{"id":12,"post_number":8,"can_boost":true,"boosts":[$json]}"""))
        assertTrue(parsed.canBoost); assertEquals(8, parsed.boosts.single().id)
        assertEquals("booster", parsed.boosts.single().author.username)
        assertEquals("/{size}.png", parsed.boosts.single().author.avatar)
        assertTrue(DiscourseParser.post(JSONObject("""{"id":1}""")).boosts.isEmpty())
        assertFalse(DiscourseParser.post(JSONObject("""{"id":1}""")).canBoost)
    }

    @Test fun creationUsesTheDedicatedEndpointAndConfirmedObjectAndInvalidInputNeverWrites() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"token"}"""))
            server.enqueue(MockResponse().setBody(json))
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            assertTrue(runCatching { repository.boost(post, "字".repeat(17), emptySet()) }.isFailure)
            assertTrue(runCatching { repository.boost(post.copy(canBoost = false), "加油", emptySet()) }.isFailure)
            assertEquals(0, server.requestCount)
            val result = repository.boost(post, " 加油😀 ", emptySet())
            assertEquals(post.number, result.number); assertEquals(post.likes, result.likes)
            assertEquals(8, result.boosts.single().id)
            assertEquals("/session/csrf", server.takeRequest().path)
            val write = server.takeRequest()
            assertEquals("POST", write.method); assertEquals("/discourse-boosts/posts/12/boosts", write.path)
            assertEquals("token", write.getHeader("X-CSRF-Token"))
            assertEquals("raw=%E5%8A%A0%E6%B2%B9%F0%9F%98%80", write.body.readUtf8())
            server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
            assertTrue(runCatching { repository.boost(post, "重试前确认", emptySet()) }.isFailure)
            assertEquals(3, server.requestCount)
        }
    }
}
