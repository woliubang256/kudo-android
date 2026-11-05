package com.kudo.android

import com.kudo.android.ui.web.MiniAppInfo
import com.kudo.android.ui.web.MiniAppLocation
import com.kudo.android.ui.web.miniAppEmbeddedUrl
import com.kudo.android.ui.web.miniAppPublicUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MiniAppInfoTest {
    private val id = "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"

    @Test fun embeddingPreservesLaunchParametersAndSharingRemovesOnlyTheInternalFlag() {
        val public = "https://linux.do/apps/$id?room=one&room=two#play"
        assertEquals("https://linux.do/apps/$id?room=one&room=two&embed_mode=true#play", miniAppEmbeddedUrl(public))
        assertEquals(public, miniAppPublicUrl(miniAppEmbeddedUrl(public)))
        assertEquals(miniAppEmbeddedUrl(public), miniAppEmbeddedUrl(miniAppEmbeddedUrl(public)))
        listOf("https://linux.do/apps", "https://linux.do/login?embed_mode=false", "https://other.test/apps/$id").forEach {
            assertEquals(it, miniAppEmbeddedUrl(it)); assertEquals(it, miniAppPublicUrl(it))
        }
    }

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
        assertNull(info.publisherAvatarUrl)
    }

    @Test fun publisherAvatarResolvesPublicTemplatesAndRejectsUnsafeSources() {
        val author = JSONObject().put("name", "发布人名字").put("username", "different_username")
            .put("avatar_template", "/user_avatar/linux.do/publisher/{size}/1.png")
        val data = JSONObject().put("app", JSONObject().put("app_id", id).put("name", "应用").put("author", author))
        assertEquals("https://linux.do/user_avatar/linux.do/publisher/128/1.png", MiniAppInfo.parse(data, id).publisherAvatarUrl)
        assertEquals("发布人名字", MiniAppInfo.parse(data, id).publisher)
        author.remove("avatar_template")
        author.put("avatar_url", "//cdn.example.test/avatar.png")
        assertEquals("https://cdn.example.test/avatar.png", MiniAppInfo.parse(data, id).publisherAvatarUrl)
        listOf("http://cdn.example.test/avatar.png", "javascript:alert(1)", "data:image/png;base64,test",
            "https://user:password@cdn.example.test/avatar.png", "null", "").forEach {
            author.put("avatar_url", it)
            assertNull(it, MiniAppInfo.parse(data, id).publisherAvatarUrl)
        }
    }

    @Test fun ratingPreservesFractionalStarsAndLargeRatingCounts() {
        val app = JSONObject().put("app_id", id).put("name", "应用")
            .put("rating_average", 4.35).put("rating_count", 3_000_000_000L)
        val info = MiniAppInfo.parse(JSONObject().put("app", app), id)
        assertEquals(4.35, info.ratingAverage!!, 0.00001)
        assertEquals(3_000_000_000L, info.ratingCount!!)
        app.put("rating_average", 0).put("rating_count", 0)
        val unrated = MiniAppInfo.parse(JSONObject().put("app", app), id)
        assertEquals(0.0, unrated.ratingAverage!!, 0.0)
        assertEquals(0L, unrated.ratingCount!!)
    }

    @Test fun missingAndMalformedRatingFieldsDoNotBecomeRealZeroRatings() {
        val app = JSONObject().put("app_id", id).put("name", "应用")
        fun info() = MiniAppInfo.parse(JSONObject().put("app", app), id)
        assertNull(info().ratingAverage)
        assertNull(info().ratingCount)
        for (invalid in listOf(-1, 5.1, "4.5", JSONObject.NULL, true)) {
            app.put("rating_average", invalid)
            assertNull(info().ratingAverage)
        }
        for (invalid in listOf(-1, 1.5, "2", JSONObject.NULL, true)) {
            app.put("rating_count", invalid)
            assertNull(info().ratingCount)
        }
    }
}
