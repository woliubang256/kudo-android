package com.kudo.android.data

import org.json.JSONObject

data class UserSummary(val daysVisited: Int?, val postsRead: Int?, val likesReceived: Int?, val posts: Int?)

internal fun parseUserSummary(json: JSONObject): UserSummary {
    val data = json.optJSONObject("user_summary") ?: throw ApiFailure(FailureKind.SERVER, "暂时无法读取个人统计")
    fun count(key: String): Int? = (data.opt(key) as? Number)?.toInt()?.takeIf { it >= 0 }
    return UserSummary(count("days_visited"), count("posts_read_count"), count("likes_received"), count("post_count"))
}

suspend fun CommunityRepository.userSummary(username: String): UserSummary {
    val url = client.baseUrl.newBuilder().addPathSegment("u").addPathSegment(username).addPathSegment("summary.json").build()
    return parseUserSummary(client.get(url.toString()))
}

enum class AccountDestination(val title: String) {
    TOPICS("话题/回复"), BOOKMARKS("我的收藏"), DRAFTS("我的草稿"), HISTORY("浏览历史"), LIKES("我的点赞"),
    MESSAGES("私信"), CHAT("聊天"), PENDING("我的待审核"), BADGES("我的徽章"),
    TRUST("等级要求"), INVITES("邀请链接")
}

suspend fun CommunityRepository.personalTopics(username: String, destination: AccountDestination,
    next: String? = null, mailbox: String = "inbox"): TopicPage {
    require(destination in setOf(AccountDestination.TOPICS, AccountDestination.HISTORY, AccountDestination.MESSAGES))
    require(mailbox in setOf("inbox", "sent", "archive"))
    val path = when (destination) {
        AccountDestination.TOPICS -> client.baseUrl.newBuilder().addPathSegment("topics")
            .addPathSegment("created-by").addPathSegment("$username.json").build().toString()
        AccountDestination.HISTORY -> "/read.json"
        else -> client.baseUrl.newBuilder().addPathSegment("topics")
            .addPathSegment(when (mailbox) { "sent" -> "private-messages-sent"; "archive" -> "private-messages-archive"; else -> "private-messages" })
            .addPathSegment("$username.json").build().toString()
    }
    return DiscourseParser.topicPage(client.get(next ?: path))
}
