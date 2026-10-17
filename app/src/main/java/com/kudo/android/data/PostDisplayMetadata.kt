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

internal fun postActionDescription(code: String): String = when (code) {
    "pinned.enabled" -> "置顶了此话题"
    "pinned.disabled" -> "取消了此话题的置顶"
    "pinned_globally.enabled" -> "全站置顶了此话题"
    "pinned_globally.disabled" -> "取消了此话题的全站置顶"
    "closed.enabled", "autoclosed.enabled" -> "关闭了此话题"
    "closed.disabled", "autoclosed.disabled" -> "重新开放了此话题"
    "archived.enabled" -> "归档了此话题"
    "archived.disabled" -> "取消了此话题的归档"
    "banner.enabled" -> "将此话题设为了横幅"
    "banner.disabled" -> "移除了话题横幅"
    "visible.enabled" -> "公开了此话题"
    "visible.disabled" -> "隐藏了此话题"
    "split_topic" -> "拆分了此话题"
    "invited_user" -> "邀请了用户加入话题"
    "invited_group" -> "邀请了群组加入话题"
    "removed_user" -> "移除了话题成员"
    "removed_group" -> "移除了话题群组"
    "user_left" -> "离开了此话题"
    "public_topic", "open_topic" -> "将此讨论转换为公开话题"
    "private_topic" -> "将此讨论转换为私信"
    "autobumped" -> "自动顶起了此话题"
    else -> "更新了话题设置"
}
