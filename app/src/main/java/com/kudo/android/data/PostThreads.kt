package com.kudo.android.data

data class PostThread(val root: Post, val replies: List<Post>)

fun missingReplyParents(posts: List<Post>): List<Int> {
    val numbers = posts.map(Post::number).toSet()
    return posts.filterNot(Post::isSystemAction).mapNotNull { post -> post.replyTo?.takeIf { it > 1 && it < post.number && it !in numbers } }.distinct()
}

/** Only explicit, earlier parent floors belong to a thread. Quotes do not establish parents. */
fun postThreads(posts: List<Post>): List<PostThread> {
    val unique = posts.distinctBy(Post::id).sortedBy(Post::number)
    val byNumber = unique.associateBy(Post::number)
    fun root(post: Post): Post {
        var current = post
        while (true) {
            if (current.isSystemAction) return current
            val target = current.replyTo?.takeIf { it > 1 && it < current.number } ?: return current
            current = byNumber[target]?.takeUnless(Post::isSystemAction) ?: return current
        }
    }
    return unique.groupBy { root(it).id }.values.map { group ->
        val first = root(group.first())
        PostThread(first, group.filterNot { it.id == first.id })
    }.sortedBy { it.root.number }
}

suspend fun CommunityRepository.postAt(topicId: Int, number: Int): Post {
    val detail = detail(topicId, number)
    require(detail.id == topicId) { "回复所属话题需重新确认" }
    return detail.posts.firstOrNull { it.number == number }
        ?: throw ApiFailure(FailureKind.SERVER, "原评论暂不可访问")
}

suspend fun CommunityRepository.replies(postId: Int, after: Int): List<Post> =
    client.getArray("/posts/$postId/replies.json?after=$after").objects().map(DiscourseParser::post)
