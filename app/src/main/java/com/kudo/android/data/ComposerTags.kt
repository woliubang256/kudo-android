package com.kudo.android.data

suspend fun CommunityRepository.composerTags(query: String, categoryId: Int, selected: List<TopicTag> = emptyList()): List<TopicTag> {
    val url = client.resolve("/tags/filter/search").newBuilder()
        .addQueryParameter("q", query.trim()).addQueryParameter("filterForInput", "true")
        .apply {
            if (categoryId > 0) addQueryParameter("categoryId", categoryId.toString())
            if (selected.all { it.id != null && it.id > 0 }) {
                selected.mapNotNull { it.id }.distinct().forEach { addQueryParameter("selected_tag_ids[]", it.toString()) }
            } else {
                selected.map { it.name }.distinct().forEach { addQueryParameter("selected_tags[]", it) }
            }
        }.build()
    return client.get(url.toString()).optJSONArray("results").objects()
        .filterNot { it.optBoolean("disabled") }.mapNotNull { it.topicTag() }.distinctBy { it.name }
}
