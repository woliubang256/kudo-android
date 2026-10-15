package com.kudo.android.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CommunityRepository(val client: DiscourseClient) {
    private val categoryLock = Any()
    private val _categoryCatalog = MutableStateFlow<List<Category>>(emptyList())
    val categoryCatalog = _categoryCatalog.asStateFlow()
    private var categoryAccount: Int? = null
    private var categoryGeneration = 0

    fun useCategoryAccount(accountId: Int?) = synchronized(categoryLock) {
        if (categoryAccount != accountId) {
            categoryAccount = accountId
            categoryGeneration++
            _categoryCatalog.value = emptyList()
        }
    }

    suspend fun topics(filter: String = "latest", categoryId: Int? = null, next: String? = null): TopicPage {
        require(FeedFilter.entries.any { it.value == filter })
        // The website's Unseen tab uses this endpoint; keep the existing UI/cache key stable.
        val endpoint = if (filter == FeedFilter.UNREAD.value) "unseen" else filter
        val path = next ?: if (categoryId == null) "/$endpoint.json" else "/c/$categoryId/l/$endpoint.json"
        return DiscourseParser.topicPage(client.get(path))
    }
    suspend fun tagTopics(slug: String, id: Int? = null, filter: String = "latest", next: String? = null): TopicPage {
        require(slug.matches(Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_-]*")))
        require(FeedFilter.entries.any { it.value == filter })
        val canonicalId = id?.takeIf { it > 0 } ?: Regex("([0-9]+)-tag").matchEntire(slug)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        val base = "/tag/$slug${canonicalId?.let { "/$it" }.orEmpty()}"
        val endpoint = if (filter == FeedFilter.UNREAD.value) "unseen" else filter
        val path = next ?: if (filter == FeedFilter.LATEST.value) "$base.json" else "$base/l/$endpoint.json"
        return DiscourseParser.topicPage(client.get(path))
    }
    suspend fun newTopicCount(user: Member): Int {
        val url = client.baseUrl.newBuilder().addPathSegment("u").addPathSegment(user.username)
            .addPathSegment("topic-tracking-state.json").build()
        return client.getArray(url.toString()).objects().distinctBy { it.getInt("topic_id") }.count { topic ->
            val level = if (topic.isNull("notification_level")) null else topic.getInt("notification_level")
            val unread = !topic.isNull("last_read_post_number") &&
                topic.getInt("last_read_post_number") < topic.optInt("highest_post_number") && (level ?: 0) >= 2
            val new = topic.isNull("last_read_post_number") && (level == null || level >= 2) &&
                topic.optBoolean("created_in_new_period") && !topic.optBoolean("is_seen")
            !topic.optBoolean("deleted") && (new || (user.unifiedNew && unread))
        }
    }
    suspend fun categories(): List<Category> {
        val token = synchronized(categoryLock) { categoryGeneration }
        val categories = DiscourseParser.categories(client.get("/site.json"))
        synchronized(categoryLock) {
            if (token == categoryGeneration && categories.isNotEmpty()) _categoryCatalog.value = categories
        }
        return categories
    }
    suspend fun tagIcons() = client.tagIcons()
    suspend fun currentUser(): Member? = client.get("/session/current.json").optJSONObject("current_user")?.let { DiscourseParser.member(it, avatarDetailsLoaded = true) }
    suspend fun user(username: String): Member {
        val path = client.baseUrl.newBuilder().addPathSegment("u").addPathSegment("$username.json").build().toString()
        return DiscourseParser.member(client.get(path).getJSONObject("user"), avatarDetailsLoaded = true)
    }
    suspend fun userCards(ids: List<Int>): List<Member> {
        val users = ids.filter { it > 0 }.distinct()
        if (users.isEmpty()) return emptyList()
        require(users.size <= 50)
        return client.get("/user-cards.json", mapOf("user_ids" to users.joinToString(",")))
            .optJSONArray("users").objects().map { DiscourseParser.member(it, avatarDetailsLoaded = true) }
    }
    suspend fun emojiGroups(): List<SiteEmojiGroup> = DiscourseParser.emojiGroups(client.get("/emojis.json"))
    suspend fun emojiUrls(): Map<String, String> = emojiGroups().flatMap { it.items }.associate { it.name to it.url }
    suspend fun detail(id: Int, number: Int? = null, trackVisit: Boolean = false) = DiscourseParser.detail(
        client.get(if (number == null) "/t/$id.json" else "/t/$id/$number.json",
            if (trackVisit) mapOf("track_visit" to "true") else emptyMap()))
    suspend fun recordReading(batch: TopicReadingBatch) {
        require(batch.topicId > 0 && batch.topicTime > 0 && batch.timings.isNotEmpty())
        require(batch.timings.all { (number, time) -> number > 0 && time in 1..60_000 })
        val fields = mutableMapOf("topic_id" to batch.topicId.toString(), "topic_time" to batch.topicTime.toString())
        batch.timings.forEach { (number, time) -> fields["timings[$number]"] = time.toString() }
        client.write("POST", "/topics/timings", fields)
    }
    suspend fun posts(topicId: Int, ids: List<Int>): List<Post> {
        if (ids.isEmpty()) return emptyList()
        val url = client.resolve("/t/$topicId/posts.json").newBuilder().apply { ids.take(20).forEach { addQueryParameter("post_ids[]", it.toString()) } }.build()
        return client.get(url.toString()).getJSONObject("post_stream").optJSONArray("posts").objects().map(DiscourseParser::post)
    }
    suspend fun post(id: Int) = DiscourseParser.post(client.get("/posts/$id.json"))
    suspend fun search(query: String, page: Int = 1): Pair<List<Topic>, Boolean> {
        val json = client.get("/search.json", mapOf("q" to query, "page" to page.toString()))
        val posts = json.optJSONArray("posts").objects().associateBy { it.optInt("topic_id") }
        val topics = json.optJSONArray("topics").objects().map { item ->
            val post = posts[item.optInt("id")]
            Topic(item.getInt("id"), item.text("title"), plainText(post?.text("blurb").orEmpty()),
                post?.let { DiscourseParser.member(it, fromPost = true) }, item.optInt("category_id"), item.optJSONArray("tags").tagNames(),
                (item.optInt("posts_count", 1) - 1).coerceAtLeast(0), item.optInt("views"), item.optInt("like_count"),
                item.text("image_url"), item.text("last_posted_at"), tagDetails = item.optJSONArray("tags").topicTags())
        }
        return topics to (json.optJSONObject("grouped_search_result")?.optBoolean("more_full_page_results") ?: false)
    }
    suspend fun like(post: Post): Post = react(post, post.activeReaction ?: "heart")

    suspend fun react(post: Post, reaction: String): Post {
        require(reaction.matches(Regex("[a-zA-Z0-9_+:-]+")))
        if (!post.canReact) throw ApiFailure(FailureKind.FORBIDDEN,
            if (post.activeReaction != null) "此回应已无法撤销或更换" else "暂时无法回应此帖子")
        val result = if (post.reactionsAvailable) {
            val url = client.baseUrl.newBuilder().addPathSegment("discourse-reactions").addPathSegment("posts")
                .addPathSegment(post.id.toString()).addPathSegment("custom-reactions").addPathSegment(reaction)
                .addPathSegment("toggle.json").build()
            client.write("PUT", url.toString())
        } else {
            require(reaction == "heart")
            if (post.liked) client.write("DELETE", "/post_actions/${post.id}?post_action_type_id=2")
            else client.write("POST", "/post_actions", mapOf("id" to post.id.toString(), "post_action_type_id" to "2"))
        }
        val updated = if (result.optInt("id") == post.id && result.has("post_number")) DiscourseParser.post(result) else post(post.id)
        if (updated.id != post.id) throw ApiFailure(FailureKind.SERVER, "回应状态需刷新确认")
        return updated
    }

    suspend fun follow(member: Member): Boolean {
        if (!member.canFollow && !member.isFollowed) throw ApiFailure(FailureKind.FORBIDDEN, "暂时无法关注此用户")
        require(member.username.isNotBlank())
        val url = client.baseUrl.newBuilder().addPathSegment("follow").addPathSegment("${member.username}.json").build()
        val result = client.write(if (member.isFollowed) "DELETE" else "PUT", url.toString())
        if (result.optString("success") != "OK") throw ApiFailure(FailureKind.SERVER, "关注状态需刷新确认")
        return !member.isFollowed
    }
    suspend fun bookmark(post: Post): Int? {
        if (post.bookmarkId != null) { client.write("DELETE", "/bookmarks/${post.bookmarkId}.json"); return null }
        val result = client.write("POST", "/bookmarks.json", mapOf("bookmarkable_id" to post.id.toString(), "bookmarkable_type" to "Post"))
        return result.positiveInt("id") ?: result.optJSONObject("bookmark")?.positiveInt("id")
            ?: throw ApiFailure(FailureKind.SERVER, "收藏状态需刷新确认")
    }
    suspend fun bookmarks(username: String, page: Int): Pair<List<SavedTopic>, Boolean> {
        val url = client.baseUrl.newBuilder().addPathSegment("u").addPathSegment(username).addPathSegment("bookmarks.json").addQueryParameter("page", page.toString()).build()
        val list = client.get(url.toString()).optJSONObject("user_bookmark_list")
        val result = list?.optJSONArray("bookmarks").objects().map {
            SavedTopic(it.getInt("id"), it.optInt("topic_id"), it.text("title"), plainText(it.text("excerpt")), it.positiveInt("post_number"))
        }.filter { it.topicId > 0 }
        return result to !list?.text("more_bookmarks_url").isNullOrBlank()
    }
    suspend fun notifications(offset: Int): List<Notice> = DiscourseParser.notices(client.get("/notifications", mapOf("offset" to offset.toString(), "limit" to "30")))
    suspend fun readNotification(id: Int? = null) { client.write("PUT", "/notifications/mark-read", id?.let { mapOf("id" to it.toString()) } ?: emptyMap()) }
    suspend fun publish(raw: String, title: String?, categoryId: Int?, topicId: Int?, replyTo: Int?, tags: List<String> = emptyList()): PublishResult {
        val fields = mutableMapOf("raw" to raw)
        if (topicId != null) fields["topic_id"] = topicId.toString() else {
            fields["title"] = title.orEmpty()
            categoryId?.let { fields["category"] = it.toString() }
        }
        replyTo?.let { fields["reply_to_post_number"] = it.toString() }
        val body = FormBody.Builder().apply {
            fields.forEach { (key, value) -> add(key, value) }
            if (topicId == null) tags.map(String::trim).filter(String::isNotBlank).distinct().forEach { add("tags[]", it) }
        }.build()
        val result = client.write("POST", "/posts.json", body = body)
        if (result.text("action") == "enqueued") return PublishResult(topicId, true)
        val post = result.optJSONObject("post") ?: result
        if (post.positiveInt("id") == null) throw ApiFailure(FailureKind.SERVER, "未确认发布结果，草稿已保留，请到社区查看")
        return PublishResult(post.positiveInt("topic_id") ?: topicId, false)
    }
    suspend fun upload(bytes: ByteArray, mimeType: String, filename: String): String {
        require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "请选择 10 MB 以内的图片" }
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) { "请选择 JPG、PNG、WebP 或 GIF 图片" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("type", "composer")
            .addFormDataPart("synchronous", "true").addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType())).build()
        val result = client.write("POST", "/uploads.json", body = body)
        val url = result.text("short_url").ifBlank { result.text("url") }
        require(url.isNotBlank()) { "上传未返回图片地址" }
        return "![图片]($url)"
    }
}
