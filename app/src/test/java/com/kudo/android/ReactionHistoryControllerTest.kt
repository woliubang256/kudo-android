package com.kudo.android

import com.kudo.android.data.*
import com.kudo.android.ui.topic.ReactionHistoryController
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReactionHistoryControllerTest {
    private val post = DiscourseParser.post(JSONObject("""{"id":1,"post_number":1}"""))
    private fun history(name: String, count: Int = 1) = ReactionHistory(listOf(ReactionHistoryGroup("heart", count,
        listOf(ReactionHistoryUser(Member(0, name, name, ""), "heart")))))

    @Test fun lateCancelledResultsCannotCrossPostsOrResurrectDismissedSheets() = runTest {
        val pending = CompletableDeferred<Unit>()
        val controller = ReactionHistoryController(this) { requested, _ ->
            if (requested.id == 1) withContext(NonCancellable) { pending.await() }
            history("post${requested.id}")
        }
        controller.open(post)
        runCurrent()
        controller.open(post.copy(id = 2))
        runCurrent()
        assertEquals("post2", controller.state.history!!.users(null).single().member.username)
        pending.complete(Unit)
        runCurrent()
        assertEquals(2, controller.state.post!!.id)
        assertEquals("post2", controller.state.history!!.users(null).single().member.username)
        controller.dismiss()
        assertNull(controller.state.post)
        assertNull(controller.state.history)
    }

    @Test fun errorsCanRetryAndFilteringDoesNotIssueWritesOrNewReads() = runTest {
        var requests = 0
        val controller = ReactionHistoryController(this) { _, _ ->
            requests++
            if (requests == 1) error("network")
            history("real-user", 90)
        }
        controller.open(post)
        runCurrent()
        assertNotNull(controller.state.error)
        controller.retry()
        runCurrent()
        assertNull(controller.state.error)
        assertEquals(90, controller.state.history!!.total)
        controller.select("heart")
        controller.select(null)
        controller.more()
        assertEquals(2, requests)
        controller.dismiss()
    }

    @Test fun coreMoreRequestsAreSerializedAndRetainExistingUsersOnFailure() = runTest {
        var attempts = 0
        val controller = ReactionHistoryController(this) { _, page ->
            attempts++
            if (page == 0) history("first", 2).copy(nextPage = 1)
            else if (attempts == 2) error("retry") else history("second", 2)
        }
        controller.open(post)
        runCurrent()
        controller.more()
        controller.more()
        runCurrent()
        assertEquals(2, attempts)
        assertEquals("first", controller.state.history!!.users(null).single().member.username)
        controller.retry()
        runCurrent()
        assertEquals(listOf("first", "second"), controller.state.history!!.users(null).map { it.member.username })
        controller.dismiss()
    }

    @Test fun stoppingAProfileSourceRetainsItsHistoryAndSelection() = runTest {
        val controller = ReactionHistoryController(this) { _, _ -> history("first") }
        controller.open(post)
        runCurrent()
        controller.select("heart")
        controller.stop()
        assertEquals(post, controller.state.post)
        assertEquals("first", controller.state.history!!.users(null).single().member.username)
        assertEquals("heart", controller.state.reaction)
        assertFalse(controller.state.loading)
    }

    @Test fun stoppingAnUnfinishedSourceRejectsLateResultsAndAllowsRetry() = runTest {
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        val controller = ReactionHistoryController(this) { _, _ ->
            if (++calls == 1) withContext(NonCancellable) { pending.await() }
            history("user$calls")
        }
        controller.open(post)
        runCurrent()
        controller.stop()
        assertEquals(post, controller.state.post)
        assertFalse(controller.state.loading)
        pending.complete(Unit)
        runCurrent()
        assertNull(controller.state.history)
        controller.retry()
        runCurrent()
        assertEquals("user2", controller.state.history!!.users(null).single().member.username)
    }
}
