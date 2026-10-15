package com.kudo.android.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.Collator
import java.util.Locale

data class DirectoryTag(val tag: TopicTag, val pmOnly: Boolean = false)
data class DirectoryTagGroup(val name: String, val tags: List<DirectoryTag>)
data class CommunityDirectory(
    val accountId: Int?, val username: String,
    val categories: List<Category>, val allCategories: List<Category>,
    val tags: List<DirectoryTag>, val showTags: Boolean,
)

internal suspend fun CommunityRepository.communityDirectory(accountId: Int?): CommunityDirectory =
    parseCommunityDirectory(client.siteSettingsHtml(), accountId)

internal suspend fun CommunityRepository.directoryTagGroups(): List<DirectoryTagGroup> =
    parseDirectoryTagGroups(client.get("/tags.json"))

private fun invalidDirectory(): Nothing = throw ApiFailure(FailureKind.SERVER, "服务器未返回有效的类别与标签目录，请重试")

internal fun parseCommunityDirectory(html: String, accountId: Int?): CommunityDirectory {
    val document = Jsoup.parse(html)
    val preload = document.selectFirst("script#data-preloaded")?.data()?.takeIf(String::isNotBlank)
        ?: document.selectFirst("[data-preloaded]")?.attr("data-preloaded")
        ?: invalidDirectory()
    val root = runCatching { JSONObject(preload) }.getOrNull() ?: invalidDirectory()
    fun objectValue(key: String): JSONObject? = when (val value = root.opt(key)) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    val user = objectValue("currentUser")
    val receivedId = user?.positiveInt("id")
    if (receivedId != accountId) throw ApiFailure(FailureKind.SIGN_IN, "登录状态已变化，请刷新后重试")
    val site = objectValue("site") ?: invalidDirectory()
    val settings = objectValue("siteSettings") ?: invalidDirectory()
    val raw = site.optJSONArray("categories") ?: invalidDirectory()
    val metadata = raw.objects().associateBy { it.optInt("id") }
    val collator = Collator.getInstance(Locale.ROOT)
    val parsed = DiscourseParser.categories(site).distinctBy { it.id }
    val ordered = if (settings.optBoolean("fixed_category_positions")) {
        parsed.sortedBy { metadata[it.id]?.optInt("position", Int.MAX_VALUE) ?: Int.MAX_VALUE }
    } else parsed.sortedWith { a, b -> collator.compare(a.name, b.name) }
    // The web sidebar orders the complete hierarchy before filtering selected IDs.
    val children = ordered.groupBy { metadata[it.id]?.positiveInt("parent_category_id") }
    val visited = mutableSetOf<Int>()
    val all = buildList {
        fun visit(category: Category) {
            if (!visited.add(category.id)) return
            add(category)
            children[category.id].orEmpty().forEach(::visit)
        }
        ordered.filter { metadata[it.id]?.positiveInt("parent_category_id") !in metadata }.forEach(::visit)
        ordered.forEach(::visit)
    }
    val uncategorized = site.positiveInt("uncategorized_category_id") ?: 1
    val visible = all.filter { settings.optBoolean("allow_uncategorized_topics", true) || it.id != uncategorized }
    val personalIds = user?.optJSONArray("sidebar_category_ids")?.let { array ->
        (0 until array.length()).mapNotNull { array.optInt(it).takeIf { id -> id > 0 } }
    }.orEmpty()
    val defaults = settings.stringField("default_navigation_menu_categories").split('|').mapNotNull(String::toIntOrNull)
    val selected = if (user != null) personalIds else defaults
    val categories = if (selected.isNotEmpty()) visible.filter { it.id in selected }
        else visible.filter { metadata[it.id]?.positiveInt("parent_category_id") == null }.take(5)
    val showTags = settings.optBoolean("tagging_enabled") && (user?.optBoolean("display_sidebar_tags", true) ?: true)
    val personalTags = parseDirectoryTags(user?.optJSONArray("sidebar_tags"))
    val tags = when {
        !showTags -> emptyList()
        user != null && personalTags.isNotEmpty() -> personalTags.sortedWith { a, b -> collator.compare(a.tag.name, b.tag.name) }
        user == null && site.optJSONArray("anonymous_default_navigation_menu_tags") != null ->
            parseDirectoryTags(site.optJSONArray("anonymous_default_navigation_menu_tags"))
        else -> parseDirectoryTags(site.optJSONArray("navigation_menu_site_top_tags"))
    }
    return CommunityDirectory(receivedId, user?.stringField("username").orEmpty(), categories, all, tags, showTags)
}

private fun parseDirectoryTags(array: JSONArray?): List<DirectoryTag> = if (array == null) emptyList() else
    (0 until array.length()).mapNotNull { index ->
        when (val value = array.opt(index)) {
            is String -> value.trim().takeIf(String::isNotEmpty)?.let { DirectoryTag(TopicTag(it)) }
            is JSONObject -> value.topicTag()?.let { DirectoryTag(it, value.optBoolean("pm_only")) }
            else -> null
        }
    }.distinctBy { it.tag.name }

internal fun parseDirectoryTagGroups(json: JSONObject): List<DirectoryTagGroup> {
    val groups = json.optJSONObject("extras")?.optJSONArray("tag_groups")
    val tags = json.optJSONArray("tags")
    if (groups == null && tags == null) invalidDirectory()
    return buildList {
        groups.objects().forEach { group ->
            val entries = parseDirectoryTags(group.optJSONArray("tags"))
            if (entries.isNotEmpty()) add(DirectoryTagGroup(group.stringField("name"), entries))
        }
        parseDirectoryTags(tags).takeIf { it.isNotEmpty() }?.let { add(DirectoryTagGroup("", it)) }
    }
}
