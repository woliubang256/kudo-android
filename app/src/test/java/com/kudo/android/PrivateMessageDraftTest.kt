package com.kudo.android

import com.kudo.android.data.*
import org.junit.Assert.*
import org.junit.Test

class PrivateMessageDraftTest {
    private val source = PostMessageSource(5, 8, "话题 & 中文？")

    @Test fun firstOpenPrefillsOnlyTheRecipientTitleAndExactPostLink() {
        val draft = initialPrivateMessageDraft(Draft(), "Reply_Author", source)
        assertEquals("Reply_Author", draft.recipients)
        assertEquals("回复：话题 & 中文？", draft.title)
        assertEquals("https://linux.do/t/topic/5/8", draft.raw)
        assertEquals("https://linux.do/t/topic/5/1", source.copy(postNumber = 1).link)
    }

    @Test fun savedDraftWinsEvenWhenItsTitleOrBodyWasIntentionallyCleared() {
        for (saved in listOf(Draft("修改后的标题", "正文", recipients = "other"),
            Draft("", "保留正文", recipients = "author"), Draft("保留标题", "", recipients = "author"),
            Draft(recipients = "other"))) {
            assertEquals(saved, initialPrivateMessageDraft(saved, "author", source))
        }
        assertEquals("author", initialPrivateMessageDraft(Draft(raw = "旧正文"), "author", source).recipients)
    }

    @Test fun keysSeparateAccountsAuthorsTopicsFloorsAndGenericMessages() {
        val keys = listOf(privateMessageDraftKey(7, "Author", source), privateMessageDraftKey(8, "Author", source),
            privateMessageDraftKey(7, "Other", source), privateMessageDraftKey(7, "Author", source.copy(topicId = 6)),
            privateMessageDraftKey(7, "Author", source.copy(postNumber = 9)), privateMessageDraftKey(7, "Author"),
            privateMessageDraftKey(7, ""))
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("7_message_author~5~8", keys.first())
        assertEquals("7_message_0", keys.last())
    }

    @Test fun ordinaryProfileAndGenericMessagesKeepExistingInitialization() {
        assertEquals(Draft(recipients = "author"), initialPrivateMessageDraft(Draft(), "author"))
        assertEquals(Draft(), initialPrivateMessageDraft(Draft(), ""))
        val saved = Draft("原标题", "原正文", recipients = "original")
        assertEquals(saved, initialPrivateMessageDraft(saved, "author"))
    }

    @Test fun sourceDraftKeepsItsKeyAndIsNotParsedAsATopicReply() {
        val drafts = localAccountDrafts(mapOf(
            "draft_7_message_some.user~5~8_title" to "回复：话题",
            "draft_7_message_some.user~5~8_raw" to "https://linux.do/t/topic/5/8",
            "draft_7_message_some.user~5~8_recipients" to "some.user",
            "draft_8_message_some.user~5~8_raw" to "另一个账号",
        ), 7)
        assertEquals("7_message_some.user~5~8", drafts.single().key)
        assertTrue(drafts.single().privateMessage)
        assertNull(drafts.single().topicId)
        assertNull(drafts.single().replyTo)
        assertEquals("some.user", drafts.single().draft.recipients)
    }

    @Test fun sourceSuffixDoesNotCreateAFakeForumReplyDraft() {
        assertTrue(localAccountDrafts(mapOf("draft_7_5_author~5~8_raw" to "无效回复"), 7).isEmpty())
    }
}
