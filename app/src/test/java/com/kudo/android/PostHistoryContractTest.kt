package com.kudo.android

import com.kudo.android.data.*
import com.kudo.android.ui.topic.parseArticle
import com.kudo.android.ui.topic.articleImages
import com.kudo.android.ui.topic.ArticleBlock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostHistoryContractTest {
    private val post = Post(10, 1, Member(2, "me", "我", ""), "", "2026-10-15T01:00:00Z", 0, false, null, null, false, version = 2, canViewEditHistory = true)
    private fun json() = JSONObject("""{"post_id":10,"current_revision":2,"previous_revision":null,"display_username":"me","acting_user_name":"我","avatar_template":"/avatar/{size}.png","created_at":"2026-10-15T02:00:00Z","edit_reason":"换图","body_changes":{"side_by_side":"<div class='revision-content --previous'><p>保留<del>旧字</del></p><img class='diff-del' src='https://linux.do/old.png'><pre><code>old</code></pre></div><div class='revision-content --current'><p>保留<ins>新字</ins></p><a href='/t/5'>链接</a><div class='spoiler'><img class='diff-ins' src='https://linux.do/new.png'></div></div>"},"title_changes":{"side_by_side":"<div class='revision-content --previous'>原标题</div><div class='revision-content --current'><ins>新标题</ins></div>"}}""")
    @Test fun completeVersionsKeepTheirOwnImagesLinksAndMarkup() {
        val result = parseHistoryRevision(post, json())
        assertEquals("新标题", result.version.title); assertEquals("原标题", result.original!!.title)
        assertTrue(result.version.cooked!!.contains("新字")); assertFalse(result.version.cooked!!.contains("旧字"))
        assertEquals("https://linux.do/new.png", parseArticle(result.version.cooked!!).filterIsInstance<ArticleBlock.Spoiler>().single().blocks.articleImages().single().url)
        assertEquals("https://linux.do/old.png", parseArticle(result.original!!.cooked!!).articleImages().single().url)
        assertTrue(result.version.cooked!!.contains("href=\"/t/5\""))
        assertTrue(result.original!!.cooked!!.contains("<code>old</code>"))
        assertTrue(result.version.cooked!!.contains("spoiler")); assertFalse(result.version.cooked!!.contains("diff-ins"))
        assertEquals("换图", result.version.reason); assertEquals("我", result.version.editor.displayName)
        assertEquals(post.createdAt, result.original!!.createdAt)
    }
    @Test fun hiddenAndComplexDiffsNeverRevealEmbeddedSnapshots() {
        for (field in listOf("previous_hidden", "current_hidden", "diff_error")) {
            val result = parseHistoryRevision(post, json().put(field, true))
            assertNull(result.version.cooked); assertNull(result.original); assertNotNull(result.version.unavailable)
        }
    }
    @Test fun missingOrTruncatedHistoryDoesNotInventOriginalOrReuseLatestBody() {
        val absent = parseHistoryRevision(post, json().apply { remove("body_changes") })
        assertNull(absent.version.cooked); assertNull(absent.original)
        val truncated = parseHistoryRevision(post, json().put("current_revision", 4))
        assertNull(truncated.original)
        assertTrue(runCatching { parseHistoryRevision(post, json().put("post_id", 99)) }.isFailure)
    }
}
