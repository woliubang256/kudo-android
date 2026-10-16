package com.kudo.android.data

import java.util.Locale

data class PostMessageSource(val topicId: Int, val postNumber: Int, val topicTitle: String) {
    init { require(topicId > 0 && postNumber > 0 && topicTitle.isNotBlank()) }
    val title get() = "回复：$topicTitle"
    val link get() = "https://linux.do/t/topic/$topicId/$postNumber"
}

internal fun privateMessageDraftKey(userId: Int, recipient: String, source: PostMessageSource? = null): String {
    val username = recipient.lowercase(Locale.ROOT).ifBlank { "0" }
    return "${userId}_message_$username" + (source?.let { "~${it.topicId}~${it.postNumber}" } ?: "")
}

internal fun initialPrivateMessageDraft(saved: Draft, recipient: String, source: PostMessageSource? = null): Draft {
    val draft = if (source != null && saved.title.isBlank() && saved.raw.isBlank() && saved.recipients.isBlank()) {
        saved.copy(title = source.title, raw = source.link)
    } else saved
    return if (draft.recipients.isBlank() && recipient.isNotBlank()) draft.copy(recipients = recipient) else draft
}
