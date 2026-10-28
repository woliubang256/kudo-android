package com.kudo.android.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class HomeTabType { FILTER, DIRECTORY, CATEGORY, TAG }

data class HomeTab(val type: HomeTabType, val value: String, val label: String, val tagId: Int? = null) {
    val key: String get() = when (type) {
        HomeTabType.FILTER, HomeTabType.DIRECTORY -> value
        HomeTabType.CATEGORY -> "category:$value"
        HomeTabType.TAG -> "tag:${tagId ?: value}"
    }

    companion object {
        fun filter(filter: FeedFilter) = HomeTab(HomeTabType.FILTER, filter.value, filter.label)
        fun directory() = HomeTab(HomeTabType.DIRECTORY, "categories", "类别")
        fun category(category: Category) = HomeTab(HomeTabType.CATEGORY, category.id.toString(), category.name)
        fun tag(tag: TopicTag) = HomeTab(HomeTabType.TAG, tag.slug.ifBlank { tag.name }, tag.name, tag.id)
    }
}

fun defaultHomeTabs(loggedIn: Boolean): List<HomeTab> = FeedFilter.available(loggedIn).map { HomeTab.filter(it) } + HomeTab.directory()

private val homeTabTagSlug = Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_-]*")

fun normalizeHomeTabs(tabs: List<HomeTab>, loggedIn: Boolean): List<HomeTab> {
    val keys = mutableSetOf<String>()
    val tagSlugs = mutableSetOf<String>()
    val tagIds = mutableSetOf<Int>()
    return tabs.mapNotNull { tab ->
        when (tab.type) {
            HomeTabType.FILTER -> FeedFilter.available(loggedIn).firstOrNull { it.value == tab.value }?.let { HomeTab.filter(it) }
            HomeTabType.DIRECTORY -> HomeTab.directory().takeIf { tab.value == it.value }
            HomeTabType.CATEGORY -> tab.value.toIntOrNull()?.takeIf { it > 0 && tab.label.isNotBlank() }
                ?.let { HomeTab(HomeTabType.CATEGORY, it.toString(), tab.label.trim()) }
            HomeTabType.TAG -> tab.takeIf {
                homeTabTagSlug.matches(it.value) && it.label.isNotBlank() && (it.tagId == null || it.tagId > 0)
            }?.copy(label = tab.label.trim())
        }
    }.filter { tab ->
        val duplicateTag = tab.type == HomeTabType.TAG &&
            (tab.value in tagSlugs || tab.tagId?.let { it in tagIds } == true)
        if (tab.key in keys || duplicateTag) false else {
            keys += tab.key
            if (tab.type == HomeTabType.TAG) {
                tagSlugs += tab.value
                tab.tagId?.let(tagIds::add)
            }
            true
        }
    }.ifEmpty { defaultHomeTabs(loggedIn) }
}

fun encodeHomeTabs(tabs: List<HomeTab>): String = JSONObject().put("version", 1)
    .put("tabs", JSONArray().apply {
        tabs.forEach { tab ->
            put(JSONObject().put("type", tab.type.name).put("value", tab.value).put("label", tab.label)
                .put("tagId", tab.tagId ?: JSONObject.NULL))
        }
    }).toString()

fun decodeHomeTabs(value: String?, loggedIn: Boolean): List<HomeTab> = runCatching {
    val source = JSONTokener(value.orEmpty())
    val json = source.nextValue() as? JSONObject ?: return@runCatching defaultHomeTabs(loggedIn)
    require(source.nextClean() == '\u0000' && json.opt("version") == 1)
    val tabs = json.getJSONArray("tabs")
    normalizeHomeTabs((0 until tabs.length()).mapNotNull { index ->
        val entry = tabs.optJSONObject(index) ?: return@mapNotNull null
        val type = HomeTabType.entries.firstOrNull { it.name == entry.opt("type") } ?: return@mapNotNull null
        val tabValue = entry.opt("value") as? String ?: return@mapNotNull null
        val tagId = if (entry.isNull("tagId")) null else (entry.opt("tagId") as? Int ?: return@mapNotNull null)
        HomeTab(type, tabValue, entry.opt("label") as? String ?: "", tagId)
    }, loggedIn)
}.getOrElse { defaultHomeTabs(loggedIn) }
