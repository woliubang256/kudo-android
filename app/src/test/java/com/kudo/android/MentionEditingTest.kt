package com.kudo.android

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kudo.android.data.*
import com.kudo.android.ui.editor.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MentionEditingTest {
    @Test fun completionReplacesOnlyTheActiveTokenAndPlacesTheCursorAfterItsSpace() {
        val value = TextFieldValue("前文 @ne_old 后文", TextRange(6))
        val query = value.mentionQuery()!!
        assertEquals("ne", query.term)
        val updated = value.completeMention(query, "neo")
        assertEquals("前文 @neo 后文", updated.text)
        assertEquals(TextRange(8), updated.selection)
        assertNull(updated.composition)
    }

    @Test fun typingAtAndChineseNamesAreSearchableButEmailsCodeLinksAndEscapesAreNot() {
        listOf("@", "你好 @ne", "(@小明", "一行\n@neo").forEach { assertNotNull(it, TextFieldValue(it, TextRange(it.length)).mentionQuery()) }
        listOf("a@neo", "a+tag@neo", "https://a/@neo", "\\@neo", "`@neo", "``code @neo", "```\n@neo",
            "~~~\n@neo", "    @neo", "[@neo](https://example.com)", "![图](https://x/@neo)").forEach {
            val offset = it.indexOf("@neo") + 4
            assertNull(it, TextFieldValue(it, TextRange(offset)).mentionQuery())
        }
    }

    @Test fun selectionCompositionAndStaleResultsCannotReplaceNewerText() {
        val value = TextFieldValue("@ne", TextRange(3))
        val query = value.mentionQuery()!!
        val newer = TextFieldValue("@other", TextRange(6))
        assertEquals(newer, newer.completeMention(query, "neo"))
        assertNull(value.copy(composition = TextRange(1, 3)).mentionQuery())
        assertNull(value.copy(selection = TextRange(0, 3)).mentionQuery())
        assertEquals(value, value.completeMention(query, "bad username"))
    }

    @Test fun scopedSearchKeepsServerOrderAndOnlyReturnsSixSafeUniqueUsers() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"users":[{"id":1,"username":"neo","name":"站长"},{"id":1,"username":"NEO"},{"id":2,"username":"bad name"},{"id":3,"username":"neyuki"}],"groups":[{"name":"staff"}]}"""))
            val users = CommunityRepository(DiscourseClient(NoCookies, server.url("/"))).composerMentions("ne", topicId = 42, categoryId = 8)
            assertEquals(listOf("neo", "neyuki"), users.map { it.username })
            assertEquals("站长", users.first().name)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/u/search/users.json", url.encodedPath)
            assertEquals("42", url.queryParameter("topic_id"))
            assertNull(url.queryParameter("category_id"))
            assertEquals("false", url.queryParameter("include_groups"))
            assertEquals("6", url.queryParameter("limit"))
        }
    }

    @Test fun initialAtCanRequestCategoryUsersOrRecentUsersWithoutAnEmptyFullSearch() = runBlocking {
        MockWebServer().use { server ->
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            repeat(2) { server.enqueue(MockResponse().setBody("""{"users":[]}""")) }
            repository.composerMentions("", categoryId = 8)
            val category = server.takeRequest().requestUrl!!
            assertEquals("8", category.queryParameter("category_id"))
            assertEquals("", category.queryParameter("term"))
            repository.composerMentions("")
            assertEquals("true", server.takeRequest().requestUrl!!.queryParameter("last_seen_users"))
        }
    }

    private object NoCookies : SessionCookies {
        override fun get(url: String) = ""
        override fun set(url: String, cookie: String) = Unit
    }
}
