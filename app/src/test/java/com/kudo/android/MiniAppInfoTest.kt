package com.kudo.android

import com.kudo.android.ui.web.MiniAppInfo
import com.kudo.android.ui.web.MiniAppLocation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MiniAppInfoTest {
    private val id = "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"

    @Test fun onlyOfficialHttpsSquareAndExactAppPathsUseTheMiniAppShell() {
        assertEquals(MiniAppLocation(null), MiniAppLocation.parse("https://linux.do/apps/"))
        assertEquals(MiniAppLocation(id), MiniAppLocation.parse("https://linux.do/apps/$id?room=abc&room=def#play"))
        listOf("http://linux.do/apps", "https://linux.do.evil.test/apps", "https://linux.do:444/apps",
            "https://user@linux.do/apps", "https://linux.do/apps/not-an-id", "https://linux.do/apps/$id/other",
            "https://linux.do/t/topic/1", "https://other.test/apps/$id").forEach { assertNull(it, MiniAppLocation.parse(it)) }
    }

    @Test fun infoUsesServerFieldsAndDoesNotAcceptAnotherAppsIdentity() {
        val data = JSONObject("""{"app":{"app_id":"$id","name":"2048","description":"2048 小游戏",
            "icon_url":"https://cdn.example.test/icon.png","version":"1.0.0","author":{"name":"Neo","username":"neo"}}}""")
        val info = MiniAppInfo.parse(data, id)
        assertEquals("2048", info.name)
        assertEquals("2048 小游戏", info.description)
        assertEquals("1.0.0", info.version)
        assertEquals("Neo", info.publisher)
        assertEquals("https://cdn.example.test/icon.png", info.iconUrl)
        assertThrows(IllegalArgumentException::class.java) { MiniAppInfo.parse(data, "01a1966e-935a-79e0-8c76-9b0f7552c578") }
    }

    @Test fun missingOptionalFieldsRemainEmptyAndUnsafeIconsAreIgnored() {
        val app = JSONObject().put("app_id", id).put("name", "应用").put("icon_url", "javascript:alert(1)")
            .put("author", JSONObject().put("username", "publisher"))
        val info = MiniAppInfo.parse(JSONObject().put("app", app), id)
        assertNull(info.iconUrl)
        assertEquals("publisher", info.publisher)
        assertEquals("", info.version)
        assertEquals("", info.description)
    }
}
