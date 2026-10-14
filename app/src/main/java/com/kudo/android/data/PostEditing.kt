package com.kudo.android.data

data class EditablePost(val post: Post, val topicId: Int, val raw: String, val title: String)

suspend fun CommunityRepository.editablePost(postId: Int, viewerId: Int): EditablePost {
    require(postId > 0 && viewerId > 0)
    val json = client.get("/posts/$postId.json")
    val post = DiscourseParser.post(json)
    if (post.id != postId) throw ApiFailure(FailureKind.SERVER, "编辑内容与当前楼层不符")
    if (!post.editableBy(viewerId)) throw ApiFailure(FailureKind.FORBIDDEN, "此内容当前无法编辑")
    if (!json.has("raw") || json.isNull("raw")) throw ApiFailure(FailureKind.SERVER, "未能读取编辑原文，请重试")
    val topicId = json.positiveInt("topic_id") ?: throw ApiFailure(FailureKind.SERVER, "未能读取所属话题")
    val title = if (post.number == 1) json.stringField("topic_title").ifBlank { detail(topicId).title } else ""
    return EditablePost(post, topicId, json.getString("raw"), title)
}

suspend fun CommunityRepository.editPost(source: EditablePost, viewerId: Int, raw: String, title: String, reason: String): Post {
    require(source.post.editableBy(viewerId)) { "此内容当前无法编辑" }
    require(raw.isNotBlank()) { "先写点内容吧" }
    require(source.post.number != 1 || title.isNotBlank()) { "请填写标题" }
    val fields = linkedMapOf("post[raw]" to raw, "post[original_text]" to source.raw, "post[edit_reason]" to reason.trim())
    if (source.post.number == 1 && title != source.title) fields["title"] = title
    val json = client.write("PUT", "/posts/${source.post.id}.json", fields).optJSONObject("post")
        ?: throw ApiFailure(FailureKind.SERVER, "保存结果尚未确认，请刷新楼层核对")
    val result = DiscourseParser.post(json)
    if (result.id != source.post.id || result.number != source.post.number || json.optInt("topic_id") != source.topicId)
        throw ApiFailure(FailureKind.SERVER, "保存结果尚未确认，请刷新楼层核对")
    return result
}
