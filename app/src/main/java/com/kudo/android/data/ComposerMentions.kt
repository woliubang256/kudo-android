package com.kudo.android.data

internal fun validMentionUsername(username: String): Boolean =
    username.matches(Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_.-]{0,59}"))

internal suspend fun CommunityRepository.composerMentions(term: String, topicId: Int? = null, categoryId: Int? = null): List<Member> {
    val parameters = mutableMapOf("term" to term.removePrefix("@"), "limit" to "6", "include_groups" to "false")
    when {
        topicId != null && topicId > 0 -> parameters["topic_id"] = topicId.toString()
        categoryId != null && categoryId > 0 -> parameters["category_id"] = categoryId.toString()
        term.isEmpty() -> parameters["last_seen_users"] = "true"
    }
    return client.get("/u/search/users.json", parameters).optJSONArray("users").objects()
        .map { DiscourseParser.member(it) }.filter { validMentionUsername(it.username) }
        .distinctBy { it.username.lowercase(java.util.Locale.ROOT) }.take(6)
}
