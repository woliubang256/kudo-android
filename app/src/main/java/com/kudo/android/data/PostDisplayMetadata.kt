package com.kudo.android.data

import org.json.JSONArray

data class PostHeaderBadge(val id: Int, val name: String, val description: String, val icon: String, val image: String, val type: Int)

internal fun parsePostHeaderBadges(values: JSONArray?): List<PostHeaderBadge> = values.objects().mapNotNull { item ->
    val badge = item.optJSONObject("badge") ?: item
    val id = badge.positiveInt("id") ?: return@mapNotNull null
    PostHeaderBadge(id, plainText(badge.stringField("name")), plainText(badge.stringField("description")),
        badge.stringField("icon").substringAfterLast(' ').removePrefix("fa-"),
        badge.stringField("image_url"), badge.optInt("badge_type_id"))
}.distinctBy { it.id }
