package com.kudo.android.data

import org.json.JSONObject
import java.util.Locale

data class ReactionHistoryUser(val member: Member, val reaction: String, val createdAt: String = "")
data class ReactionHistoryGroup(val reaction: String, val count: Int, val users: List<ReactionHistoryUser>)
data class ReactionHistory(val groups: List<ReactionHistoryGroup>, val nextPage: Int? = null) {
    val total: Int get() = groups.sumOf { it.count }
    val partial: Boolean get() = nextPage == null && groups.any { it.count > it.users.size }
    fun users(reaction: String?): List<ReactionHistoryUser> = groups.filter { reaction == null || it.reaction == reaction }
        .flatMap { it.users }.sortedByDescending { it.createdAt }.distinctBy { it.member.username.lowercase(Locale.ROOT) }

    fun append(page: ReactionHistory): ReactionHistory = ReactionHistory(page.groups.map { group ->
        group.copy(users = (groups.firstOrNull { it.reaction == group.reaction }?.users.orEmpty() + group.users)
            .distinctBy { it.member.username.lowercase(Locale.ROOT) })
    }, page.nextPage)
}

fun Post.popularReactions(): List<PostReaction> = (if (reactionsAvailable) reactions else listOf(PostReaction("heart", likes)))
    .filter { it.id.isNotBlank() && it.count > 0 }.distinctBy { it.id }.sortedByDescending { it.count }.take(3)

suspend fun CommunityRepository.reactionHistory(post: Post, page: Int = 0): ReactionHistory {
    require(post.id > 0 && page >= 0)
    if (post.reactionsAvailable) {
        require(page == 0)
        return parseReactionHistory(client.get("/discourse-reactions/posts/${post.id}/reactions-users.json"))
    }
    val size = 50
    val json = client.get("/post_action_users.json", mapOf("id" to post.id.toString(), "post_action_type_id" to "2",
        "page" to page.toString(), "limit" to size.toString()))
    val values = json.optJSONArray("post_action_users") ?: throw ApiFailure(FailureKind.SERVER, "站点未返回点赞名单")
    val users = values.objects().mapNotNull { parseReactionHistoryUser(it, "heart") }
    val total = json.positiveInt("total_rows_post_action_users") ?: post.reactionCount
    val next = (page + 1).takeIf { values.length() == size && total > (page + 1) * size }
    return ReactionHistory(listOf(ReactionHistoryGroup("heart", total, users)), next)
}

internal fun parseReactionHistory(json: JSONObject): ReactionHistory {
    val groups = json.optJSONArray("reaction_users") ?: throw ApiFailure(FailureKind.SERVER, "站点未返回回应名单")
    return ReactionHistory(groups.objects().mapNotNull { group ->
        val reaction = group.stringField("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val users = group.optJSONArray("users").objects().mapNotNull { parseReactionHistoryUser(it, reaction) }
            .distinctBy { it.member.username.lowercase(Locale.ROOT) }
        ReactionHistoryGroup(reaction, group.optInt("count").coerceAtLeast(0), users)
    }.distinctBy { it.reaction }.sortedByDescending { it.count })
}

private fun parseReactionHistoryUser(json: JSONObject, reaction: String): ReactionHistoryUser? {
    if (json.stringField("username").isBlank() || json.optBoolean("unknown")) return null
    return ReactionHistoryUser(DiscourseParser.member(json, avatarDetailsLoaded = true), reaction, json.stringField("created_at"))
}
