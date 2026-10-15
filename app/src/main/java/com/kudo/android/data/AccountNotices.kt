package com.kudo.android.data

enum class NoticeGroup(val title: String, private val types: Set<Int>) {
    ALL("全部", emptySet()), REPLIES("回复", setOf(1, 2, 3, 15)),
    LIKES("赞", setOf(5, 19, 25)), MESSAGES("私信", setOf(6, 7, 16));
    fun includes(notice: Notice) = this == ALL || notice.type in types
}
data class NoticePage(val notices: List<Notice>, val next: String?)

suspend fun CommunityRepository.noticePage(offset: Int, next: String? = null): NoticePage {
    val json = if (next == null) client.get("/notifications", mapOf("offset" to offset.toString(), "limit" to "30")) else client.get(next)
    val array = json.optJSONArray("notifications") ?: throw ApiFailure(FailureKind.SERVER, "服务器未返回通知列表")
    val result = DiscourseParser.notices(json)
    val total = (json.opt("total_rows_notifications") as? Number)?.toInt()
    val hasMore = if (total != null) offset + 30 < total else array.length() == 30
    val cursor = if (hasMore) json.text("load_more_notifications").ifBlank { "/notifications?offset=${offset + 30}&limit=30" } else null
    return NoticePage(result, cursor?.takeUnless { it == next })
}
