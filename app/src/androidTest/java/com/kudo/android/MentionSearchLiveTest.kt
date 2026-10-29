package com.kudo.android

import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.composerMentions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class MentionSearchLiveTest {
    @Test fun productionRouteReturnsTypedAndInitialMentionSuggestions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mentionsLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as KudoApplication).container.repository
        val typed = withTimeout(30_000) { repository.composerMentions("ne") }
        val initial = withTimeout(30_000) { repository.composerMentions("") }
        println("Live mention suggestions: typed=${typed.size}, initial=${initial.size}")
        assertTrue("Typed mentions must have candidates", typed.isNotEmpty())
        assertTrue("Initial @ must have candidates", initial.isNotEmpty())
        assertTrue(typed.size <= 6 && initial.size <= 6)
    }

    @Test fun typedSearchChangesRecentCandidatesAndAnUnknownNameHasNoResults() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mentionsLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as KudoApplication).container.repository
        val category = withTimeout(30_000) { repository.categories() }.first().id
        val topic = withTimeout(30_000) { repository.topics() }.topics.first().id
        for ((categoryId, topicId) in listOf(null to null, category to null, null to topic)) {
            suspend fun query(term: String) = withTimeout(30_000) {
                repository.composerMentions(term, topicId = topicId, categoryId = categoryId)
            }
            val initial = query("")
            val typed = query("ne")
            val unknown = query("zzzzNoSuchMember991083")
            println("Mention filtering category=$categoryId topic=$topicId initial=${initial.map { it.username }} typed=${typed.map { it.username }} unknown=${unknown.map { it.username }}")
            assertNotEquals("Typing must change the recent-user list", initial.map { it.username }, typed.map { it.username })
            assertTrue("An unknown username must not return recent users", unknown.isEmpty())
        }
    }
}
