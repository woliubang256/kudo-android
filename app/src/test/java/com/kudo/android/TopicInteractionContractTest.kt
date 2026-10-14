package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TopicInteractionContractTest {
    private val reactedJson = """{"id":10,"post_number":1,"user_id":2,"username":"author","actions_summary":[{"id":2,"count":9,"acted":true,"can_undo":true}],"reactions":[{"id":"heart","type":"emoji","count":7},{"id":"distorted_face","type":"emoji","count":2}],"reaction_users_count":9,"current_user_reaction":{"id":"distorted_face","type":"emoji","can_undo":true}}"""

    @Test fun anotherReactionIsNotMistakenForAHeartEvenWhenItCountsAsALike() {
        val post = DiscourseParser.post(JSONObject(reactedJson))
        assertFalse("其他回应同步到 actions_summary 不代表选中了爱心", post.liked)
    }

    @Test fun shortTapCancelsTheActualEmojiInsteadOfDeletingACoreLike() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"test-token"}"""))
            server.enqueue(MockResponse().setBody("""{"id":10,"post_number":1,"username":"author","reactions":[],"current_user_reaction":null}"""))
            CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
                .like(DiscourseParser.post(JSONObject(reactedJson)))
            assertEquals("/session/csrf", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/discourse-reactions/posts/10/custom-reactions/distorted_face/toggle.json", request.path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun parsedReactionRetainsTotalsAndUndoPermission() {
        val post = DiscourseParser.post(JSONObject(reactedJson))
        assertEquals("distorted_face", post.activeReaction)
        assertEquals(9, post.reactionCount)
        assertEquals(listOf(PostReaction("heart", 7), PostReaction("distorted_face", 2)), post.reactions)
        assertTrue(post.reactionsAvailable)
        assertTrue(post.canReact)
        val locked = DiscourseParser.post(JSONObject(reactedJson.replace("\"can_undo\":true", "\"can_undo\":false")))
        assertFalse(locked.canReact)
    }

    @Test fun successfulToggleUsesTheServerPostAndDoesNotIncrementLocally() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"test-token"}"""))
            server.enqueue(MockResponse().setBody(reactedJson.replace("\"reaction_users_count\":9", "\"reaction_users_count\":15")))
            val updated = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
                .react(DiscourseParser.post(JSONObject(reactedJson)), "+1")
            assertEquals(15, updated.reactionCount)
            assertEquals("distorted_face", updated.activeReaction)
            server.takeRequest()
            assertEquals("/discourse-reactions/posts/10/custom-reactions/+1/toggle.json", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun forbiddenUndoAndInvalidReactionDoNotMakeRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            val post = DiscourseParser.post(JSONObject(reactedJson))
            val failure = runCatching { repository.like(post.copy(currentReaction = UserReaction("distorted_face", false))) }.exceptionOrNull()
            assertEquals(FailureKind.FORBIDDEN, (failure as ApiFailure).kind)
            assertTrue(runCatching { repository.react(post, "../heart?other=true") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun deepLinkKeepsOriginalAuthorEvenWhenFirstLoadedPostIsAReply() {
        val detail = DiscourseParser.detail(JSONObject("""{"id":5,"title":"深链","created_at":"2026-10-01T00:00:00Z","details":{"created_by":{"id":2,"username":"author"}},"valid_reactions":["heart","distorted_face","heart","../bad"],"post_stream":{"stream":[10,11,12],"posts":[{"id":12,"post_number":3,"user_id":9,"username":"reply-author"}]}}"""))
        assertEquals("author", detail.author!!.username)
        assertEquals(2, detail.author.id)
        assertEquals("2026-10-01T00:00:00Z", detail.createdAt)
        assertNull(detail.originalPost)
        assertEquals(listOf("heart", "distorted_face"), detail.validReactions)
    }

    @Test fun firstPostRetainsRichAvatarMetadataAndLegacyLikes() {
        val detail = DiscourseParser.detail(JSONObject("""{"id":5,"details":{"created_by":{"id":2,"username":"author"}},"post_stream":{"stream":[10],"posts":[{"id":10,"post_number":1,"user_id":2,"username":"author","flair_url":"battery-quarter","actions_summary":[{"id":2,"count":3,"acted":true,"can_undo":true}]}]}}"""))
        assertEquals("battery-quarter", detail.author!!.flair!!.source)
        assertEquals("heart", detail.originalPost!!.activeReaction)
        assertEquals(3, detail.originalPost!!.reactionCount)
        assertTrue(detail.originalPost!!.canReact)
    }

    @Test fun followAndUnfollowUseTheProfilePermissionsAndConfirmedResult() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"test-token"}"""))
            repeat(2) { server.enqueue(MockResponse().setBody("""{"success":"OK"}""")) }
            val member = DiscourseParser.member(JSONObject("""{"id":2,"username":"author.name","can_follow":true,"is_followed":false}"""))
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            assertTrue(repository.follow(member))
            assertFalse(repository.follow(member.copy(isFollowed = true)))
            server.takeRequest()
            val follow = server.takeRequest()
            assertEquals("PUT", follow.method)
            assertEquals("/follow/author.name.json", follow.path)
            assertEquals("DELETE", server.takeRequest().method)
        }
    }

    @Test fun failedToggleIsNotRetriedAndFollowRequiresAConfirmedSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"csrf":"test-token"}"""))
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"errors":["slow down"]}"""))
            server.enqueue(MockResponse().setBody("{}"))
            var now = 0L
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/"),
                cooldown = com.kudo.android.network.RequestCooldown({ now })))
            val failure = runCatching { repository.like(DiscourseParser.post(JSONObject(reactedJson))) }.exceptionOrNull()
            assertEquals(FailureKind.RATE_LIMIT, (failure as ApiFailure).kind)
            assertEquals(2, server.requestCount)
            assertTrue(runCatching { repository.follow(Member(2, "author", "", "", canFollow = true)) }.isFailure)
            assertEquals("限流中关注不能发送", 2, server.requestCount)
            now = 15_000
            assertTrue(runCatching { repository.follow(Member(2, "author", "", "", canFollow = true)) }.isFailure)
            assertEquals(3, server.requestCount)
        }
    }
}
