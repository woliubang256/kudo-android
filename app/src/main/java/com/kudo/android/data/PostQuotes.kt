package com.kudo.android.data

internal fun postQuoteMarkdown(topicId: Int, post: Post, selectedText: String): String {
    val username = post.author.username
    val name = post.author.displayName.takeUnless { it.any { character -> character in "\"[],\r\n" } } ?: username
    return "[quote=\"$name, post:${post.number}, topic:$topicId, username:$username\"]\n$selectedText\n[/quote]"
}

internal fun appendPostQuote(draft: String, quote: String): String {
    val separator = when {
        draft.isEmpty() || draft.endsWith("\n\n") -> ""
        draft.endsWith('\n') -> "\n"
        else -> "\n\n"
    }
    return "$draft$separator$quote\n\n"
}
