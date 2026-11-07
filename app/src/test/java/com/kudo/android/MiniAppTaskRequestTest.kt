package com.kudo.android

import com.kudo.android.ui.web.MiniAppTaskRequest
import org.junit.Assert.*
import org.junit.Test

class MiniAppTaskRequestTest {
    private val id = "01a1a755-8210-7a3f-877b-6b09df8dce08"
    private val url = "https://linux.do/apps/$id"

    @Test fun taskIdentityIgnoresLaunchParametersAndPreservesTheirActualValues() {
        val first = MiniAppTaskRequest.parse("$url?room=one&room=two&embed_mode=true#play", 8)!!
        val second = MiniAppTaskRequest.parse("$url?room=three#next", 8)!!
        assertEquals(first.key, second.key)
        assertEquals(first.key.identity, second.key.identity)
        assertEquals("$url?room=one&room=two#play", first.url)
        assertEquals("$url?room=three#next", second.url)
    }

    @Test fun accountAndApplicationIdentitiesStaySeparateAndShortcutIdsStayStable() {
        val guest = MiniAppTaskRequest.parse(url, null)!!.key
        val user = MiniAppTaskRequest.parse(url, 8)!!.key
        val other = MiniAppTaskRequest.parse(url.replace(id, "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"), 8)!!.key
        assertEquals(3, setOf(guest.identity, user.identity, other.identity).size)
        assertEquals(3, setOf(guest.shortcutId, user.shortcutId, other.shortcutId).size)
        assertEquals(user.shortcutId, MiniAppTaskRequest.parse("$url?from=shortcut", 8)!!.key.shortcutId)
    }

    @Test fun onlyExactOfficialApplicationUrlsAndValidAccountsAreAccepted() {
        listOf("https://linux.do/apps", "https://linux.do/apps/x", "$url/path", "http://linux.do/apps/$id",
            "https://linux.do.evil.test/apps/$id", "https://user@linux.do/apps/$id").forEach {
            assertNull(it, MiniAppTaskRequest.parse(it, 8))
        }
        assertNull(MiniAppTaskRequest.parse(url, 0))
        assertNull(MiniAppTaskRequest.parse(url, -1))
    }
}
