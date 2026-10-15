package com.kudo.android

import com.kudo.android.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommunityDirectoryTest {
    @Test fun tagDirectoryRetainsNameSlugAndCanonicalId() {
        val tag = parseDirectoryTagGroups(JSONObject("""{"tags":[{"id":444,"name":"公告","slug":"444-tag"}]}"""))
            .single().tags.single().tag
        assertEquals(TopicTag("公告", "444-tag", id = 444), tag)
    }

    private val site = """{"categories":[{"id":98,"name":"国产替代","position":3},{"id":20,"name":"开发 Lv1","parent_category_id":4,"position":2},{"id":4,"name":"开发调优","position":1,"icon":"code"},{"id":2,"name":"运营反馈","position":4}],"navigation_menu_site_top_tags":[{"name":"热门甲"},{"name":"热门乙"}],"anonymous_default_navigation_menu_tags":[{"name":"站点乙"},{"name":"站点甲"}]}"""
    private val settings = """{"fixed_category_positions":true,"tagging_enabled":true,"default_navigation_menu_categories":"98|4"}"""
    private fun html(user: String = "null", config: String = settings, catalog: String = site, script: Boolean = true): String {
        val preload = JSONObject().put("site", catalog).put("siteSettings", config).put("currentUser", user).toString()
        return if (script) "<script type=\"application/json\" id=\"data-preloaded\">$preload</script>"
        else "<div data-preloaded=\"${preload.replace("&", "&amp;").replace("\"", "&quot;")}\"></div>"
    }

    @Test fun personalSelectionsFollowSitePositionInsteadOfNumericIdOrSelectionArrayOrder() {
        val result = parseCommunityDirectory(html("""{"id":7,"sidebar_category_ids":[2,98,4,999],"sidebar_tags":[{"name":"原创"},{"name":"人工智能"},{"name":"公告"}]}"""), 7)
        assertEquals(listOf(4, 98, 2), result.categories.map { it.id })
        assertEquals(listOf(4, 20, 98, 2), result.allCategories.map { it.id })
        assertEquals(listOf("人工智能", "公告", "原创"), result.tags.map { it.tag.name })
        assertEquals("code", result.categories.first().icon)
    }

    @Test fun guestConfigurationWorksWithBothPreloadFormatsAndPreservesServerTagOrder() {
        for (script in listOf(true, false)) {
            val result = parseCommunityDirectory(html(script = script), null)
            assertEquals(listOf(4, 98), result.categories.map { it.id })
            assertEquals(listOf("站点乙", "站点甲"), result.tags.map { it.tag.name })
        }
    }

    @Test fun hiddenAndDisabledTagsStayHiddenAndEmptyPersonalSelectionUsesWebFallback() {
        val user = """{"id":7,"sidebar_category_ids":[],"sidebar_tags":[],"display_sidebar_tags":false}"""
        val result = parseCommunityDirectory(html(user), 7)
        assertEquals(listOf(4, 98, 2), result.categories.map { it.id })
        assertTrue(result.tags.isEmpty())
        assertFalse(result.showTags)
        assertFalse(parseCommunityDirectory(html(config = """{"tagging_enabled":false}"""), null).showTags)
    }

    @Test fun foreignOrMissingAccountSnapshotIsRejected() {
        for ((user, account) in listOf("{\"id\":8}" to 7, "null" to 7, "{\"id\":7}" to null)) {
            try { parseCommunityDirectory(html(user), account); fail("不能发布其他账号快照") }
            catch (e: ApiFailure) { assertEquals(FailureKind.SIGN_IN, e.kind) }
        }
    }

    @Test fun fullTagGroupsKeepServerOrderAndDeduplicateOnlyWithinTheirGroup() {
        val groups = parseDirectoryTagGroups(JSONObject("""{"tags":[{"name":"未分组乙"},{"name":"未分组甲"}],"extras":{"tag_groups":[{"name":"第二组","tags":[{"name":"乙"},{"name":"甲"},{"name":"乙"}]},{"name":"第一组","tags":[{"name":"甲"}]}]}}"""))
        assertEquals(listOf("第二组", "第一组", ""), groups.map { it.name })
        assertEquals(listOf("乙", "甲"), groups.first().tags.map { it.tag.name })
        assertEquals("甲", groups[1].tags.single().tag.name)
        assertEquals(listOf("未分组乙", "未分组甲"), groups.last().tags.map { it.tag.name })
    }

    @Test fun malformedSnapshotsFailInsteadOfPretendingTheDirectoryIsEmpty() {
        for (input in listOf("<html>验证页面</html>", html(catalog = "{}"))) {
            try { parseCommunityDirectory(input, null); fail("目录缺失必须可重试") }
            catch (e: ApiFailure) { assertEquals(FailureKind.SERVER, e.kind) }
        }
        try { parseDirectoryTagGroups(JSONObject()); fail("标签集合缺失必须报错") }
        catch (e: ApiFailure) { assertEquals(FailureKind.SERVER, e.kind) }
    }
}
