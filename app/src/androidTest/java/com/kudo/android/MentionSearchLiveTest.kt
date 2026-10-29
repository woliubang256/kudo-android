package com.kudo.android

import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.composerMentions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
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
}
