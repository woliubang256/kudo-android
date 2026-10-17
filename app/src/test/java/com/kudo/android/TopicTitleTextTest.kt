package com.kudo.android

import com.kudo.android.data.AvatarMetadataCache
import com.kudo.android.ui.components.parseTopicTitle
import org.junit.Assert.*
import org.junit.Test

class TopicTitleTextTest {
    private val fire = "https://linux.do/images/emoji/twemoji/fire.png?v=15"

    @Test fun reportedTitleKeepsItsRawTextAndAddsAnInlineFire() {
        val title = ":fire:【省钱百科19】关于Gemini Ultra 5x 20x和Antigravity拼车，你想知道的一切【gemini-4-argon版】"
        val content = parseTopicTitle(title, mapOf("fire" to AvatarMetadataCache.standardEmojiUrl("fire")!!))
        assertEquals(title, content.text.text)
        assertEquals("fire", content.emojis.values.single().name)
        assertEquals(fire, content.emojis.values.single().url)
        assertEquals(listOf(":fire:"), content.text.getStringAnnotations(0, title.length).map { title.substring(it.start, it.end) })
    }

    @Test fun repeatedAndAdjacentEmojiKeepSeparateInlineRanges() {
        val title = ":fire::custom:中间:fire:"
        val content = parseTopicTitle(title, mapOf("fire" to fire, "custom" to "https://emoji.test/custom.gif"))
        assertEquals(title, content.text.text)
        assertEquals(2, content.emojis.size)
        assertEquals(listOf(":fire:", ":custom:", ":fire:"),
            content.text.getStringAnnotations(0, title.length).map { title.substring(it.start, it.end) })
    }

    @Test fun skinTonesAndPlusMinusNamesArePreserved() {
        val names = listOf("+1:t3", "-1", "custom_name")
        val title = names.joinToString(" ") { ":$it:" }
        val content = parseTopicTitle(title, names.associateWith { AvatarMetadataCache.standardEmojiUrl(it)!! })
        assertEquals(names, content.emojis.values.map { it.name })
        assertEquals("https://linux.do/images/emoji/twemoji/+1/3.png?v=15", content.emojis.values.first().url)
        assertEquals(title, content.text.text)
    }

    @Test fun plainTextUnicodeHtmlAndUnknownNamesRemainLiteral() {
        val title = "🔥 10:30 https://example.com <tag> &amp; :unknown: :fire:"
        val content = parseTopicTitle(title, mapOf("fire" to fire))
        assertEquals(title, content.text.text)
        assertEquals(listOf("fire"), content.emojis.values.map { it.name })
    }

    @Test fun failedImagesFallBackWithoutHidingOtherEmoji() {
        val title = ":missing: 标题 :fire:"
        val missing = "https://emoji.test/missing.png"
        val content = parseTopicTitle(title, mapOf("missing" to missing, "fire" to fire), setOf(missing))
        assertEquals(title, content.text.text)
        assertEquals(listOf("fire"), content.emojis.values.map { it.name })
        assertEquals(":fire:", content.text.getStringAnnotations(0, title.length).single().let { title.substring(it.start, it.end) })
    }

    @Test fun unsafeEmojiAddressesKeepTheirShortcodes() {
        val title = ":script: :http: :blank: :fire:"
        val content = parseTopicTitle(title, mapOf("script" to "javascript:alert(1)", "http" to "http://emoji.test/a.png", "blank" to "", "fire" to fire))
        assertEquals(title, content.text.text)
        assertEquals(listOf("fire"), content.emojis.values.map { it.name })
    }

    @Test fun aRecoveredCustomImageIsNotBlockedByAnEarlierStandardFailure() {
        val custom = "https://emoji.test/custom.gif"
        val content = parseTopicTitle(":custom:", mapOf("custom" to custom), setOf("https://linux.do/images/emoji/twemoji/custom.png?v=15"))
        assertEquals(custom, content.emojis.values.single().url)
    }
}
