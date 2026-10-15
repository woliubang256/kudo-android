package com.kudo.android.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class SavedDraft(val key: String, val draft: Draft, val topicId: Int?, val replyTo: Int?,
    val remote: Boolean, val sequence: Int = 0, val source: String = "{}", val time: String = "",
    val privateMessage: Boolean = false, val backupKey: String? = null, val categoryContext: Int? = null) {
    val title get() = draft.title.ifBlank { topicId?.let { "话题 #$it 的回复" } ?: if (privateMessage) "未命名私信" else "未命名草稿" }
}

internal fun localAccountDrafts(values: Map<String, *>, userId: Int): List<SavedDraft> {
    val pattern = Regex("^draft_${userId}_(new|message|[0-9]+)_([\\p{L}\\p{N}_.-]+)_(?:raw|title)$")
    val drafts = values.keys.mapNotNull { pattern.matchEntire(it) }.map { it.groupValues[1] to it.groupValues[2] }
        .filter { (topic, reply) -> topic == "message" || reply.toIntOrNull() != null }.distinct()
        .mapNotNull { (topic, reply) ->
            val key = "${userId}_${topic}_$reply"
            val draft = Draft(values["draft_${key}_title"] as? String ?: "", values["draft_${key}_raw"] as? String ?: "",
                values["draft_${key}_category"] as? Int ?: 0, values["draft_${key}_recipients"] as? String ?: "", decodeDraftTags(values["draft_${key}_tags"] as? String))
            if (draft.raw.isBlank() && draft.title.isBlank()) null else SavedDraft(key, draft, topic.toIntOrNull(),
                reply.toIntOrNull()?.takeIf { it > 0 && topic.toIntOrNull() != null }, false, privateMessage = topic == "message",
                categoryContext = reply.toIntOrNull()?.takeIf { it > 0 && topic == "new" })
        }
    val backupPrefix = "draft_${userId}_cloud_"
    val backups = values.keys.filter { it.startsWith(backupPrefix) && it.endsWith("_raw") }.mapNotNull { rawKey ->
        val remoteKey = rawKey.removePrefix(backupPrefix).removeSuffix("_raw")
        val prefix = rawKey.removeSuffix("_raw")
        val draft = Draft(values["${prefix}_title"] as? String ?: "", values[rawKey] as? String ?: "", values["${prefix}_category"] as? Int ?: 0,
            tags = decodeDraftTags(values["${prefix}_tags"] as? String))
        if (draft.raw.isBlank() && draft.title.isBlank()) null else SavedDraft(remoteKey, draft,
            remoteKey.removePrefix("topic_").toIntOrNull(), null, true, privateMessage = remoteKey.startsWith("new_private_message"), backupKey = "${userId}_cloud_$remoteKey")
    }
    return drafts + backups
}

internal fun parseCloudDraft(item: JSONObject): SavedDraft {
    val source = when (val data = item.opt("data")) {
        is JSONObject -> data
        is String -> runCatching { JSONObject(data) }.getOrNull()
        else -> null
    } ?: throw ApiFailure(FailureKind.SERVER, "草稿内容无法读取，请在网页查看")
    val key = item.text("draft_key").takeIf(String::isNotBlank) ?: throw ApiFailure(FailureKind.SERVER, "草稿缺少标识")
    val topicId = item.positiveInt("topic_id") ?: key.removePrefix("topic_").toIntOrNull()?.takeIf { it > 0 }
    return SavedDraft(key, Draft(source.text("title").ifBlank { item.text("title") }, source.text("reply"), source.optInt("categoryId"), tags = source.optJSONArray("tags").tagNames()),
        topicId, source.positiveInt("replyToPostNumber"), true, item.optInt("draft_sequence", item.optInt("sequence")),
        source.toString(), item.text("updated_at").ifBlank { item.text("created_at") },
        item.text("archetype") == "private_message" || key.startsWith("new_private_message") || source.text("action") == "private_message")
}

suspend fun CommunityRepository.cloudDrafts(offset: Int): Pair<List<SavedDraft>, Boolean> {
    val json = client.get("/drafts.json", mapOf("offset" to offset.toString(), "limit" to "20"))
    val array = json.optJSONArray("drafts") ?: throw ApiFailure(FailureKind.SERVER, "服务器未返回草稿列表")
    return array.objects().map(::parseCloudDraft) to json.optBoolean("has_more")
}
suspend fun CommunityRepository.cloudDraft(key: String): SavedDraft {
    val url = client.baseUrl.newBuilder().addPathSegment("drafts").addPathSegment("$key.json").build()
    val json = client.get(url.toString())
    return parseCloudDraft(JSONObject().put("draft_key", key).put("data", json.opt("draft")).put("draft_sequence", json.optInt("draft_sequence")))
}
internal fun updatedCloudData(saved: SavedDraft, edited: Draft): JSONObject = JSONObject(saved.source)
    .put("title", edited.title).put("reply", edited.raw).put("categoryId", edited.categoryId)

suspend fun CommunityRepository.saveCloudDraft(saved: SavedDraft, edited: Draft): SavedDraft {
    val data = updatedCloudData(saved, edited)
    val result = client.write("POST", "/drafts.json", mapOf("draft_key" to saved.key,
        "sequence" to saved.sequence.toString(), "data" to data.toString()))
    if (!result.has("draft_sequence")) throw ApiFailure(FailureKind.SERVER, "草稿保存结果需刷新确认，本机副本已保留")
    return saved.copy(draft = edited, sequence = result.getInt("draft_sequence"), source = data.toString())
}
suspend fun CommunityRepository.deleteCloudDraft(saved: SavedDraft) {
    val url = client.baseUrl.newBuilder().addPathSegment("drafts").addPathSegment("${saved.key}.json")
        .addQueryParameter("sequence", saved.sequence.toString()).build()
    client.write("DELETE", url.toString())
}
suspend fun CommunityRepository.publishCloudDraft(saved: SavedDraft, edited: Draft): PublishResult {
    val source = JSONObject(saved.source)
    val fields = JSONObject().put("raw", edited.raw).put("draft_key", saved.key)
    if (saved.topicId != null) {
        fields.put("topic_id", saved.topicId)
        saved.replyTo?.let { fields.put("reply_to_post_number", it) }
    } else {
        fields.put("title", edited.title)
        if (saved.privateMessage) {
            fields.put("archetype", "private_message").put("target_recipients", source.optJSONArray("recipients")?.strings()?.joinToString(",").orEmpty())
        } else fields.put("category", edited.categoryId)
        source.optJSONArray("tags")?.let { fields.put("tags", it) }
    }
    val json = client.write("POST", "/posts.json", body = fields.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
    if (json.text("action") == "enqueued") return PublishResult(saved.topicId, true)
    val post = json.optJSONObject("post") ?: json
    if (post.positiveInt("id") == null) throw ApiFailure(FailureKind.SERVER, "发布结果未确认，本机草稿已保留，请刷新后查看")
    return PublishResult(post.positiveInt("topic_id") ?: saved.topicId, false)
}
