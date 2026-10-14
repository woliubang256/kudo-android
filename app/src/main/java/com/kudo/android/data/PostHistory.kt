package com.kudo.android.data

import org.json.JSONObject
import org.jsoup.Jsoup

data class HistoryVersion(val number: Int, val editor: Member, val createdAt: String, val reason: String,
    val title: String, val cooked: String?, val unavailable: String? = null, val original: Boolean = false)
data class HistoryRevision(val revision: PostRevision, val version: HistoryVersion, val original: HistoryVersion?)

private fun revisionHtml(changes: JSONObject?, previous: Boolean): String? {
    val html = changes?.stringField("side_by_side")?.takeIf(String::isNotBlank) ?: return null
    val selector = if (previous) ".revision-content.--previous" else ".revision-content.--current"
    val fragment = Jsoup.parseBodyFragment(html).selectFirst(selector) ?: return null
    fragment.select("ins, del").forEach { it.unwrap() }
    fragment.select(".diff-ins, .diff-del").forEach { it.removeClass("diff-ins"); it.removeClass("diff-del") }
    return fragment.html()
}

internal fun parseHistoryRevision(post: Post, json: JSONObject): HistoryRevision {
    val revision = parsePostRevision(json)
    if (revision.postId != post.id) throw ApiFailure(FailureKind.SERVER, "编辑历史与当前楼层不符")
    val unavailable = when {
        revision.hidden -> "此版本包含隐藏内容，无法展示正文"
        revision.diffError -> "此版本内容过于复杂，暂时无法展示"
        else -> null
    }
    val body = json.optJSONObject("body_changes")
    val title = json.optJSONObject("title_changes")
    val currentHtml = if (unavailable == null) revisionHtml(body, false) else null
    val previousHtml = if (unavailable == null) revisionHtml(body, true) else null
    val editor = Member(0, json.stringField("display_username").ifBlank { json.stringField("username") },
        json.stringField("acting_user_name"), json.stringField("avatar_template"), avatarDetailsLoaded = true)
    val version = HistoryVersion(revision.current, editor, revision.createdAt, revision.reason,
        if (unavailable == null) revisionHtml(title, false)?.let(::plainText).orEmpty() else "",
        currentHtml, unavailable ?: "此版本正文暂不可用".takeIf { currentHtml == null })
    val original = if (revision.previous == null && revision.current == 2 && unavailable == null && previousHtml != null)
        HistoryVersion(1, post.author, post.createdAt, "", revisionHtml(title, true)?.let(::plainText).orEmpty(), previousHtml, original = true)
    else null
    return HistoryRevision(revision, version, original)
}

suspend fun CommunityRepository.historyRevision(post: Post, number: Int? = null): HistoryRevision {
    require(post.hasEditHistory) { "当前楼层没有可查看的编辑历史" }
    require(number == null || number > 0)
    val result = parseHistoryRevision(post, client.get("/posts/${post.id}/revisions/${number ?: "latest"}.json"))
    if (number != null && result.revision.current != number) throw ApiFailure(FailureKind.SERVER, "历史版本不匹配，请重试")
    return result
}
