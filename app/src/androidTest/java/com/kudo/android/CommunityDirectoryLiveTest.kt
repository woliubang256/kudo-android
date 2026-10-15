package com.kudo.android

import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CommunityDirectoryLiveTest {
    @Test fun liveReadOnlySnapshotMatchesSelectedServerCategoriesAndPermissions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("directoryLiveReadOnlyProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as KudoApplication).container.repository
        val member = repository.currentUser()
        val html = repository.client.siteSettingsHtml()
        val result = parseCommunityDirectory(html, member?.id)
        val page = Jsoup.parse(html)
        val root = JSONObject(page.selectFirst("script#data-preloaded")?.data()
            ?: page.selectFirst("[data-preloaded]")!!.attr("data-preloaded"))
        val site = JSONObject(root.getString("site"))
        val current = root.optString("currentUser").let { runCatching { JSONObject(it) }.getOrNull() }
        val ids = current?.optJSONArray("sidebar_category_ids")
        if (ids != null && ids.length() > 0) {
            val selected = (0 until ids.length()).map(ids::getInt).toSet()
            assertTrue(result.categories.all { it.id in selected })
            val allowed = DiscourseParser.categories(site).map { it.id }.toSet()
            assertEquals(selected.intersect(allowed), result.categories.map { it.id }.toSet())
        }
        assertEquals(member?.id, result.accountId)
        val evidence = JSONObject().put("categories", JSONArray(result.categories.map { it.name }))
            .put("tags", JSONArray(result.tags.map { it.tag.name })).put("show_tags", result.showTags)
        File(context.getExternalFilesDir(null), "directory-server-evidence.json").writeText(evidence.toString(2))
    }
}
