package com.kudo.android.data

import java.time.LocalDate

data class SearchScope(val value: String, val label: String, val personal: Boolean = false)
val SearchScopes = listOf(
    SearchScope("created", "我创建的", true), SearchScope("posted", "我参与的", true),
    SearchScope("likes", "我赞过的", true), SearchScope("bookmarks", "我收藏的", true),
    SearchScope("seen", "我看过的", true), SearchScope("unseen", "我未看过的", true),
    SearchScope("watching", "我关注的", true), SearchScope("tracking", "我追踪的", true),
    SearchScope("all", "包括私信", true), SearchScope("messages", "仅私信", true),
    SearchScope("first", "仅首帖"), SearchScope("pinned", "已置顶"), SearchScope("wiki", "维基帖子"),
    SearchScope("images", "包含图片"), SearchScope("title", "仅标题"),
)
val SearchStatuses = listOf("open" to "未关闭", "closed" to "已关闭", "public" to "公开", "archived" to "已归档",
    "noreplies" to "无回复", "single_user" to "仅一个用户", "solved" to "已解决", "unsolved" to "未解决")

data class SearchFilters(
    val category: String = "", val tags: List<String> = emptyList(), val allTags: Boolean = false,
    val scopes: Set<String> = emptySet(), val status: String = "", val username: String = "",
    val timeWhen: String = "before", val date: String = "", val minPosts: String = "", val maxPosts: String = "",
    val minViews: String = "", val maxViews: String = "", val assigned: String = "", val expertResponse: Boolean = false,
) {
    val count: Int get() = listOf(category.isNotBlank(), tags.isNotEmpty(), scopes.isNotEmpty(), status.isNotBlank(),
        username.isNotBlank(), date.isNotBlank(), minPosts.isNotBlank() || maxPosts.isNotBlank(),
        minViews.isNotBlank() || maxViews.isNotBlank(), assigned.isNotBlank(), expertResponse).count { it }

    fun validationError(): String? {
        if (date.isNotBlank() && runCatching { LocalDate.parse(date) }.isFailure) return "日期请使用 YYYY-MM-DD 格式"
        for ((minimum, maximum, label) in listOf(Triple(minPosts, maxPosts, "帖子数量"), Triple(minViews, maxViews, "浏览量"))) {
            if (listOf(minimum, maximum).any { it.isNotBlank() && (it.toIntOrNull()?.takeIf { n -> n >= 0 } == null) }) return "${label}请输入非负整数"
            if (minimum.isNotBlank() && maximum.isNotBlank() && minimum.toInt() > maximum.toInt()) return "${label}的最小值不能大于最大值"
        }
        if (listOf(username, assigned).any { it.isNotBlank() && !it.matches(Regex("[\\p{L}\\p{N}_.-]+")) }) return "用户名或群组名不能包含空格和搜索符号"
        return null
    }

    fun tokens(): List<String> = buildList {
        if (category.isNotBlank()) add("category:$category")
        if (tags.isNotEmpty()) add("tags:${tags.joinToString(if (allTags) "+" else ",")}")
        scopes.forEach { add(if (it == "images") "with:images" else "in:$it") }
        if (status.isNotBlank()) add("status:$status")
        if (username.isNotBlank()) add("@$username")
        if (date.isNotBlank()) add("$timeWhen:$date")
        if (minPosts.isNotBlank()) add("min_posts:$minPosts")
        if (maxPosts.isNotBlank()) add("max_posts:$maxPosts")
        if (minViews.isNotBlank()) add("min_views:$minViews")
        if (maxViews.isNotBlank()) add("max_views:$maxViews")
        if (assigned.isNotBlank()) add("assigned:$assigned")
        if (expertResponse) add("with:category_expert_response")
    }
}

// Whitespace inside quotes stays literal, including text that resembles a search operator.
internal fun searchTokens(query: String): List<String> {
    val result = mutableListOf<String>()
    val token = StringBuilder()
    var quoted = false
    var escaped = false
    query.forEach { char ->
        if (char.isWhitespace() && !quoted) {
            if (token.isNotEmpty()) { result += token.toString(); token.clear() }
        } else {
            token.append(char)
            if (char == '"' && !escaped) quoted = !quoted
        }
        escaped = char == '\\' && !escaped
    }
    if (token.isNotEmpty()) result += token.toString()
    return result
}

private fun managed(token: String): Boolean {
    if (token.startsWith('"')) return false
    if (token.lowercase() in setOf("l", "r")) return true
    val key = token.substringBefore(':').lowercase()
    if (token.contains('"') && key !in setOf("category", "categories")) return false
    return token.startsWith('@') || token.startsWith('#') || key in setOf("category", "categories", "tag", "tags", "status", "user",
        "before", "after", "min_posts", "max_posts", "min_views", "max_views", "assigned", "order") ||
        (key == "in" && token.substringAfter(':').lowercase() in SearchScopes.map { it.value }) ||
        token.lowercase() in setOf("with:images", "with:category_expert_response")
}

fun parseSearchFilters(query: String): SearchFilters {
    var filters = SearchFilters()
    searchTokens(query).filter(::managed).forEach { token ->
        val value = token.substringAfter(':', "")
        filters = when {
            token.startsWith('@') -> filters.copy(username = token.drop(1))
            token.startsWith('#') && token.endsWith("::tag") -> filters.copy(tags = filters.tags + token.drop(1).removeSuffix("::tag"))
            token.startsWith('#') -> filters.copy(category = token.drop(1))
            else -> when (token.substringBefore(':').lowercase()) {
                "category", "categories" -> filters.copy(category = value)
                "tag", "tags" -> filters.copy(tags = (filters.tags + value.split(',', '+')).distinct(),
                    allTags = filters.allTags || value.contains('+') || (filters.tags.isNotEmpty() && !value.contains(',')))
                "in" -> filters.copy(scopes = filters.scopes + value.lowercase())
                "with" -> if (value.equals("images", true)) filters.copy(scopes = filters.scopes + "images") else filters.copy(expertResponse = true)
                "status" -> filters.copy(status = value.lowercase())
                "user" -> filters.copy(username = value)
                "before", "after" -> filters.copy(timeWhen = token.substringBefore(':').lowercase(), date = value)
                "min_posts" -> filters.copy(minPosts = value)
                "max_posts" -> filters.copy(maxPosts = value)
                "min_views" -> filters.copy(minViews = value)
                "max_views" -> filters.copy(maxViews = value)
                "assigned" -> filters.copy(assigned = value)
                else -> filters
            }
        }
    }
    return filters
}

fun searchSort(query: String): SearchSort? = searchTokens(query).lastOrNull {
    (it.startsWith("order:", true) && !it.contains('"')) || it.lowercase() in setOf("l", "r")
}?.let { token -> when (token.lowercase()) {
    "l" -> SearchSort.LATEST
    "r" -> SearchSort.READ
    else -> SearchSort.entries.firstOrNull { it.token.equals(token.substringAfter(':'), true) }
} }

fun withSearchFilters(query: String, filters: SearchFilters, sort: SearchSort): String {
    val tokens = searchTokens(query)
    val tagClauses = tokens.filter { managed(it) && it.substringBefore(':').lowercase() in setOf("tag", "tags") }
    val original = parseSearchFilters(query)
    // (a OR b) AND c cannot be flattened into one comma/plus list. Keep it when only other fields changed.
    val preserveTags = tagClauses.size > 1 && tagClauses.any { it.contains(',') } &&
        original.tags == filters.tags && original.allTags == filters.allTags
    val filterTokens = if (preserveTags) filters.tokens().filterNot { it.startsWith("tags:") } + tagClauses else filters.tokens()
    return (tokens.filterNot(::managed) + filterTokens + "order:${sort.token}").joinToString(" ")
}

fun withSearchSort(query: String, sort: SearchSort): String =
    (searchTokens(query).filterNot { (it.startsWith("order:", true) && !it.contains('"')) || it.lowercase() in setOf("l", "r") } + "order:${sort.token}").joinToString(" ")
