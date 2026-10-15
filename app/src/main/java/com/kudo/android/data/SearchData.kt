package com.kudo.android.data

import org.json.JSONObject
import org.jsoup.Jsoup

enum class SearchKind(val label: String) {
    POSTS("帖子"), CATEGORIES_TAGS("类别与标签"), USERS("用户")
}

enum class SearchSort(val label: String, val token: String, val personal: Boolean = false) {
    RELEVANCE("相关性", "relevance"), LATEST("最新帖子", "latest"), LIKES("赞最多", "likes"),
    VIEWS("浏览最多", "views"), LATEST_TOPIC("最新话题", "latest_topic"), READ("最近阅读", "read", true);
    companion object { fun available(loggedIn: Boolean) = entries.filter { loggedIn || !it.personal } }
}

data class SearchHit(val postId: Int, val postNumber: Int, val topic: Topic)
data class SearchResults(
    val posts: List<SearchHit> = emptyList(), val categories: List<Category> = emptyList(),
    val tags: List<TopicTag> = emptyList(), val users: List<Member> = emptyList(), val hasMore: Boolean = false,
)

data class SearchCapabilities(
    val tagging: Boolean = true, val solved: Boolean = false,
    val assignTo: Boolean = false, val expertResponse: Boolean = false,
)

internal fun parseSearchPosts(json: JSONObject, page: Int): SearchResults {
    val grouped = json.optJSONObject("grouped_search_result")
    grouped?.stringField("error")?.takeIf(String::isNotEmpty)?.let { throw ApiFailure(FailureKind.SERVER, it) }
    val topics = json.optJSONArray("topics").objects().associateBy { it.optInt("id") }
    val hits = json.optJSONArray("posts").objects().mapNotNull { post ->
        val item = topics[post.optInt("topic_id")] ?: return@mapNotNull null
        val id = post.positiveInt("id") ?: return@mapNotNull null
        SearchHit(id, post.optInt("post_number", 1).coerceAtLeast(1), Topic(
            item.getInt("id"), plainText(item.stringField("title")), plainText(post.stringField("blurb")),
            DiscourseParser.member(post, fromPost = true), item.optInt("category_id"), item.optJSONArray("tags").tagNames(),
            (item.optInt("posts_count", 1) - 1).coerceAtLeast(0), item.optInt("views"), post.optInt("like_count"),
            item.stringField("image_url"), post.stringField("created_at"), closed = item.optBoolean("closed"),
            tagDetails = item.optJSONArray("tags").topicTags(),
        ))
    }.distinctBy { it.postId }
    return SearchResults(posts = hits, hasMore = page < 10 && grouped?.optBoolean("more_full_page_results") == true)
}

suspend fun CommunityRepository.searchResults(query: String, kind: SearchKind, page: Int = 1): SearchResults {
    require(query.isNotBlank() && page in 1..10)
    return when (kind) {
        SearchKind.POSTS -> parseSearchPosts(client.get("/search.json", mapOf("q" to query, "page" to page.toString())), page)
        SearchKind.USERS -> SearchResults(users = client.get("/u/search/users.json", mapOf(
            "term" to query.removePrefix("@"), "limit" to "20", "include_groups" to "false",
        )).optJSONArray("users").objects().map { DiscourseParser.member(it) }.filter { it.username.isNotBlank() })
        SearchKind.CATEGORIES_TAGS -> {
            val catalog = categoryCatalog.value.ifEmpty { categories() }
            val matching = catalog.filter { it.name.contains(query, true) || it.slug.contains(query, true) }
            SearchResults(categories = matching, tags = searchTags(query))
        }
    }
}

suspend fun CommunityRepository.searchTags(query: String): List<TopicTag> =
    client.get("/tags/filter/search", mapOf("q" to query))
        .optJSONArray("results").objects().mapNotNull { it.topicTag() }.distinctBy { it.name }

suspend fun CommunityRepository.recentSearches(accountId: Int?): List<String> {
    if (accountId == null) return emptyList()
    return client.get("/u/recent-searches.json").optJSONArray("recent_searches").let { array ->
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.trim()?.takeIf(String::isNotBlank) }.distinct()
    }
}

suspend fun CommunityRepository.clearRecentSearches(accountId: Int?) {
    if (accountId == null) return
    client.write("DELETE", "/u/recent-searches.json")
}

internal fun parseSearchCapabilities(html: String, accountId: Int?): SearchCapabilities {
    val settings = siteSettings(html)
    val document = Jsoup.parse(html)
    val data = JSONObject(document.selectFirst("[data-preloaded]")?.attr("data-preloaded")
        ?: document.getElementById("data-preloaded")?.data().orEmpty())
    val current = data.optJSONObject("currentUser") ?: (data.opt("currentUser") as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
    return SearchCapabilities(
        tagging = settings.optBoolean("tagging_enabled", true),
        solved = settings.optBoolean("solved_enabled"),
        assignTo = accountId != null && current?.optInt("id") == accountId && current.optBoolean("can_assign_globally"),
        expertResponse = settings.optBoolean("enable_category_experts") && settings.optBoolean("show_category_expert_advanced_search_filters"),
    )
}

suspend fun CommunityRepository.searchCapabilities(accountId: Int?): SearchCapabilities =
    parseSearchCapabilities(client.siteSettingsHtml(), accountId)
