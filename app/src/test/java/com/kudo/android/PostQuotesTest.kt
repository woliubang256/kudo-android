package com.kudo.android

import com.kudo.android.data.*
import org.junit.Assert.*
import org.junit.Test

class PostQuotesTest {
    private val post = Post(27, 10, Member(8, "NullUser", "用户已注销", ""), "<p>整帖不能进入引用</p>", "", 0, false, null, null, true)

    @Test fun quoteContainsActualSourceAndOnlySelectedText() {
        assertEquals("[quote=\"用户已注销, post:10, topic:503400, username:NullUser\"]\n取值最简单\n[/quote]",
            postQuoteMarkdown(503400, post, "取值最简单"))
    }

    @Test fun quotePreservesUnicodeWhitespaceAndMultipleLines() {
        val selection = "  中文😀 <&>\n\t第二行\n"
        assertTrue(postQuoteMarkdown(42, post.copy(number = 1), selection)
            .endsWith("\n$selection\n[/quote]"))
        assertTrue(postQuoteMarkdown(42, post.copy(number = 1), selection).contains("post:1, topic:42"))
    }

    @Test fun unsafeOrMissingDisplayNameUsesUsernameWithoutBreakingMetadata() {
        for (name in listOf("", "昵称\"注入", "昵称, post:99", "昵称[头衔]", "昵称\n换行")) {
            val source = post.copy(author = post.author.copy(name = name))
            assertTrue(postQuoteMarkdown(42, source, "正文").startsWith("[quote=\"NullUser, post:10, topic:42, username:NullUser\"]\n"))
        }
    }

    @Test fun appendingKeepsExistingDraftAndSeparatesQuoteFromReply() {
        val quote = postQuoteMarkdown(42, post, "被引用")
        assertEquals("$quote\n\n", appendPostQuote("", quote))
        for (draft in listOf("已有草稿", "已有草稿\n", "已有草稿\n\n")) {
            assertEquals("已有草稿\n\n$quote\n\n", appendPostQuote(draft, quote))
        }
        assertEquals("  原文  \n\n$quote\n\n", appendPostQuote("  原文  ", quote))
        assertEquals("$quote\n\n$quote\n\n", appendPostQuote(appendPostQuote("", quote), quote))
    }
}
