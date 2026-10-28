package com.kudo.android

import com.kudo.android.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeTabsTest {
    private val category = Category(7, "开发调优", "development", "", "", 0)
    private val tag = TopicTag("公告", "444-tag", id = 444)

    @Test fun defaultsKeepTheOriginalFilterOrderAndDirectoryForEachIdentity() {
        assertEquals(listOf("latest", "hot", "top", "categories"), defaultHomeTabs(false).map(HomeTab::key))
        assertEquals(listOf("unread", "latest", "new", "hot", "top", "posted", "read", "bookmarks", "categories"),
            defaultHomeTabs(true).map(HomeTab::key))
        for (loggedIn in listOf(false, true)) {
            val defaults = defaultHomeTabs(loggedIn)
            assertEquals(FeedFilter.available(loggedIn).map { HomeTab.filter(it) }, defaults.dropLast(1))
            assertEquals(HomeTab(HomeTabType.DIRECTORY, "categories", "类别"), defaults.last())
            assertEquals(defaults, normalizeHomeTabs(emptyList(), loggedIn))
            assertEquals(defaults, decodeHomeTabs(null, loggedIn))
            assertEquals(defaults, decodeHomeTabs(encodeHomeTabs(defaults), loggedIn))
        }
    }

    @Test fun sortingRemovingAndAddingBackBuiltInsPreserveOnlyTheChosenOrder() {
        val directory = HomeTab.directory()
        val latest = HomeTab.filter(FeedFilter.LATEST)
        val top = HomeTab.filter(FeedFilter.TOP)
        val hot = HomeTab.filter(FeedFilter.HOT)
        val reordered = listOf(directory, top, latest)
        assertEquals(reordered, normalizeHomeTabs(reordered, false))
        assertEquals(reordered, decodeHomeTabs(encodeHomeTabs(reordered), false))
        val restored = reordered + hot
        assertEquals(restored, normalizeHomeTabs(restored, false))
        assertEquals(restored, decodeHomeTabs(encodeHomeTabs(restored), false))
        assertEquals(listOf(directory), normalizeHomeTabs(listOf(directory), false))
        assertEquals(listOf(top), normalizeHomeTabs(listOf(top), true))
        assertEquals(listOf("latest", "hot", "top", "categories"), defaultHomeTabs(false).map(HomeTab::key))
    }

    @Test fun mixedCustomTabsRoundTripWithoutLosingLabelsIdsOrOrder() {
        val tabs = listOf(HomeTab.tag(tag), HomeTab.filter(FeedFilter.BOOKMARKS), HomeTab.category(category),
            HomeTab.directory(), HomeTab.tag(TopicTag("人工智能")))
        val encoded = encodeHomeTabs(tabs)
        val json = JSONObject(encoded)
        assertEquals(1, json.getInt("version"))
        assertEquals(444, json.getJSONArray("tabs").getJSONObject(0).getInt("tagId"))
        assertEquals("TAG", json.getJSONArray("tabs").getJSONObject(0).getString("type"))
        assertEquals("444-tag", json.getJSONArray("tabs").getJSONObject(0).getString("value"))
        assertEquals("公告", json.getJSONArray("tabs").getJSONObject(0).getString("label"))
        assertEquals(tabs, decodeHomeTabs(encoded, true))
        assertEquals(tabs.filterNot { it.type == HomeTabType.FILTER }, decodeHomeTabs(encoded, false))
    }

    @Test fun keysSurviveDisplayChangesAndCanonicalTagSlugChanges() {
        val categoryTab = HomeTab.category(category)
        val tagTab = HomeTab.tag(tag)
        assertEquals("latest", HomeTab.filter(FeedFilter.LATEST).key)
        assertEquals("categories", HomeTab.directory().key)
        assertEquals("category:7", categoryTab.key)
        assertEquals(categoryTab.key, HomeTab.category(category.copy(name = "新分类名", slug = "renamed")).key)
        assertEquals("tag:444", tagTab.key)
        assertEquals(tagTab.key, HomeTab.tag(tag.copy(name = "新标签名", slug = "renamed")).key)
        assertEquals("tag:人工智能", HomeTab.tag(TopicTag("人工智能")).key)
        assertEquals("tag:ai", HomeTab.tag(TopicTag("人工智能", "ai")).key)
        assertNotEquals(categoryTab.key, HomeTab(HomeTabType.TAG, "7", "数字标签").key)
    }

    @Test fun normalizationKeepsTheFirstValidEntryForEachStableKey() {
        val firstTag = HomeTab.tag(tag)
        val firstCategory = HomeTab.category(category)
        val tabs = listOf(HomeTab(HomeTabType.CATEGORY, "bad", "无效"), firstTag,
            HomeTab.tag(tag.copy(name = "重命名", slug = "renamed")), firstCategory,
            firstCategory.copy(value = "007", label = "重复分类"), HomeTab.filter(FeedFilter.HOT),
            HomeTab.filter(FeedFilter.HOT), HomeTab.directory(), HomeTab.directory())
        val expected = listOf(firstTag, firstCategory, HomeTab.filter(FeedFilter.HOT), HomeTab.directory())
        assertEquals(expected, normalizeHomeTabs(tabs, true))
        assertEquals(expected, decodeHomeTabs(encodeHomeTabs(tabs), true))
    }

    @Test fun tagsWithAndWithoutIdsDeduplicateBySlugAndKeepTheFirstIdentity() {
        val identified = HomeTab.tag(tag)
        val unidentified = identified.copy(tagId = null)
        for (tabs in listOf(listOf(unidentified, identified), listOf(identified, unidentified))) {
            val expected = listOf(tabs.first())
            assertEquals(expected, normalizeHomeTabs(tabs, false))
            assertEquals(expected, decodeHomeTabs(encodeHomeTabs(tabs), false))
            assertEquals(tabs.first().key, normalizeHomeTabs(tabs, false).single().key)
        }
    }

    @Test fun tagDeduplicationMatchesEitherSlugOrNonNullIdWithoutMatchingLabels() {
        val first = HomeTab.tag(tag)
        val sameSlug = first.copy(tagId = 555, label = "另一个 ID")
        val sameId = first.copy(value = "renamed", label = "另一个 slug")
        val different = first.copy(value = "different", tagId = 666)
        val withoutId = first.copy(value = "without-id", tagId = null)
        val tabs = listOf(first, sameSlug, sameId, different, withoutId)
        val expected = listOf(first, different, withoutId)
        assertEquals(expected, normalizeHomeTabs(tabs, true))
        assertEquals(expected, decodeHomeTabs(encodeHomeTabs(tabs), true))
    }

    @Test fun builtInLabelsAndDirectoryMetadataAlwaysComeFromTheApp() {
        val tabs = listOf(HomeTab(HomeTabType.FILTER, "latest", "伪造标签", 444),
            HomeTab(HomeTabType.DIRECTORY, "categories", "伪造目录", 444))
        val expected = listOf(HomeTab.filter(FeedFilter.LATEST), HomeTab.directory())
        assertEquals(expected, normalizeHomeTabs(tabs, false))
        assertEquals(expected, decodeHomeTabs(encodeHomeTabs(tabs), false))
        assertEquals(expected, decodeHomeTabs("""{"version":1,"tabs":[
            {"type":"FILTER","value":"latest","label":false},
            {"type":"DIRECTORY","value":"categories"}]}""", false))
    }

    @Test fun guestsNeverKeepPersonalFiltersAndMembersKeepTheirChosenSubset() {
        val personal = FeedFilter.entries.filter(FeedFilter::personal).map { HomeTab.filter(it) }
        val custom = listOf(HomeTab.category(category), HomeTab.tag(tag))
        assertEquals(personal, normalizeHomeTabs(personal, true))
        assertEquals(defaultHomeTabs(false), normalizeHomeTabs(personal, false))
        assertEquals(defaultHomeTabs(false), decodeHomeTabs(encodeHomeTabs(personal), false))
        assertEquals(custom, normalizeHomeTabs(personal + custom, false))
        assertEquals(custom, decodeHomeTabs(encodeHomeTabs(personal + custom), false))
    }

    @Test fun malformedOrUnknownRootSchemasFallBackForTheCurrentIdentity() {
        val invalid = listOf(null, "", "broken", "{", "[]", "null", "true", "{}",
            """{"tabs":[]}""", """{"version":1}""", """{"version":1,"tabs":[]}""", """{"version":2,"tabs":[]}""",
            """{"version":"1","tabs":[]}""", """{"version":1.0,"tabs":[]}""",
            """{"version":true,"tabs":[]}""", """{"version":null,"tabs":[]}""",
            """{"version":4294967297,"tabs":[]}""", """{"version":1,"tabs":null}""",
            """{"version":1,"tabs":{}}""", """{"version":1,"tabs":"[]"}""",
            """{"version":1,"tabs":[]} trailing""",
            """{"version":1,"tabs":[{"type":"FUTURE","value":"latest","label":"未知"}]}""")
        for (loggedIn in listOf(false, true)) {
            for (value in invalid) assertEquals(value, defaultHomeTabs(loggedIn), decodeHomeTabs(value, loggedIn))
        }
    }

    @Test fun corruptEntriesAreSkippedWithoutDiscardingValidCustomChoices() {
        val json = """{"version":1,"tabs":[null,true,7,"bad",[],{},
            {"type":"FUTURE","value":"latest","label":"未知"},
            {"type":1,"value":"latest","label":"类型错误"},
            {"type":"FILTER","value":7,"label":"数值"},
            {"type":"CATEGORY","value":"7","label":false},
            {"type":"TAG","value":"safe","label":"标签","tagId":"444"},
            {"type":"TAG","value":"safe","label":"标签","tagId":444.5},
            {"type":"TAG","value":"safe","label":"标签","tagId":true},
            {"type":"TAG","value":"safe","label":"标签","tagId":4294967297},
            {"type":"CATEGORY","value":"7","label":"开发调优"},
            {"type":"TAG","value":"444-tag","label":"公告","tagId":444}]}"""
        assertEquals(listOf(HomeTab.category(category), HomeTab.tag(tag)), decodeHomeTabs(json, true))
    }

    @Test fun categoryIdsMustBePositiveIntegersAndNormalizeBeforeDeduplication() {
        val invalid = listOf("", "0", "-7", "1.5", "2147483648", "https://linux.do", "7/latest", " 7", "7 ")
        for (value in invalid) {
            val tab = HomeTab(HomeTabType.CATEGORY, value, "分类")
            assertEquals(value, defaultHomeTabs(false), normalizeHomeTabs(listOf(tab), false))
            assertEquals(value, defaultHomeTabs(false), decodeHomeTabs(encodeHomeTabs(listOf(tab)), false))
        }
        assertEquals(listOf(HomeTab.category(category)), normalizeHomeTabs(listOf(
            HomeTab.category(category).copy(value = "007"), HomeTab.category(category)), false))
        assertEquals(listOf(HomeTab(HomeTabType.CATEGORY, Int.MAX_VALUE.toString(), "最大 ID")),
            normalizeHomeTabs(listOf(HomeTab(HomeTabType.CATEGORY, Int.MAX_VALUE.toString(), "最大 ID")), false))
    }

    @Test fun tagSlugsUseTheRepositoryAllowlistEvenWhenACanonicalIdIsPresent() {
        val invalid = listOf("", " ", "tag name", "../latest", "a/b", "a\\b", "https://linux.do", "a?x=1",
            "a#fragment", "%2F", "a&b", "a.b", "a\n", "a\u0000", "😀")
        for (value in invalid) {
            val tab = HomeTab(HomeTabType.TAG, value, "标签", 444)
            assertEquals(value, defaultHomeTabs(false), normalizeHomeTabs(listOf(tab), false))
            assertEquals(value, defaultHomeTabs(false), decodeHomeTabs(encodeHomeTabs(listOf(tab)), false))
        }
        val valid = listOf("444-tag", "人工智能", "AI_2026", "_", "-", "é").map { HomeTab(HomeTabType.TAG, it, it) }
        assertEquals(valid, normalizeHomeTabs(valid, false))
        assertEquals(valid, decodeHomeTabs(encodeHomeTabs(valid), false))
    }

    @Test fun invalidTagIdsAndEmptyCustomLabelsDoNotCreateTabs() {
        val invalid = listOf(HomeTab.tag(tag.copy(id = 0)), HomeTab.tag(tag.copy(id = -1)),
            HomeTab.tag(tag).copy(label = " "), HomeTab.category(category).copy(label = "\t"),
            HomeTab(HomeTabType.FILTER, "unknown", "未知"), HomeTab(HomeTabType.DIRECTORY, "https://linux.do", "目录"))
        assertEquals(defaultHomeTabs(true), normalizeHomeTabs(invalid, true))
        assertEquals(defaultHomeTabs(true), decodeHomeTabs(encodeHomeTabs(invalid), true))
        val custom = listOf(HomeTab.category(category).copy(label = "  开发调优  "), HomeTab.tag(tag).copy(label = " 公告 "))
        assertEquals(listOf(HomeTab.category(category), HomeTab.tag(tag)), normalizeHomeTabs(custom, false))
    }

    @Test fun missingOrNullTagIdsKeepValidSlugBasedKeys() {
        val tabs = JSONArray().put(JSONObject().put("type", "TAG").put("value", "ai").put("label", "人工智能"))
            .put(JSONObject().put("type", "TAG").put("value", "公告").put("label", "公告").put("tagId", JSONObject.NULL))
        val json = JSONObject().put("version", 1).put("tabs", tabs).toString()
        assertEquals(listOf(HomeTab.tag(TopicTag("人工智能", "ai")), HomeTab.tag(TopicTag("公告"))), decodeHomeTabs(json, false))
    }
}
