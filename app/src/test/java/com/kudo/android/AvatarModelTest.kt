package com.kudo.android

import com.kudo.android.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AvatarModelTest {
    @Test fun postAuthorUsesUserIdInsteadOfPostId() {
        val post = DiscourseParser.post(JSONObject("""{"id":23284884,"user_id":1,"username":"neo","post_number":1}"""))
        assertEquals(23284884, post.id)
        assertEquals("用户资料必须关联作者，不是帖子本身", 1, post.author.id)
    }

    @Test fun missingPostAuthorIdCannotIdentifyAnUnrelatedUser() {
        val post = DiscourseParser.post(JSONObject("""{"id":218,"username":"deleted-user"}"""))
        assertEquals(0, post.author.id)
        assertTrue(post.author.avatarDetailsLoaded)
    }

    @Test fun originalAnimationAndBothKindsOfFlairSurviveParsing() {
        val neo = DiscourseParser.member(JSONObject("""{"id":1,"username":"Neo","animated_avatar":"//cdn.example/avatar.png","flair_name":"admins","flair_url":"battery-quarter","flair_bg_color":"00aeff","flair_color":"fff"}"""))
        assertEquals("//cdn.example/avatar.png", neo.animatedAvatar)
        assertEquals(AvatarFlair("admins", "battery-quarter", "00aeff", "fff"), neo.flair)
        assertTrue(neo.isSiteOwner)
        val member = DiscourseParser.member(JSONObject("""{"id":218,"username":"ordinary-admin","admin":true,"flair_url":"https://cdn.example/flair.gif"}"""))
        assertFalse(member.isSiteOwner)
        assertEquals("https://cdn.example/flair.gif", member.flair!!.source)
    }

    @Test fun profileAndPostStatusesHaveEquivalentExpiryRules() {
        val status = """{"description":"午休","emoji":"sleeping","ends_at":"2026-10-08T05:00:00Z"}"""
        val profile = DiscourseParser.member(JSONObject("""{"id":7,"status":$status}"""))
        val post = DiscourseParser.post(JSONObject("""{"id":99,"user_id":7,"user_status":$status}"""))
        assertEquals(profile.status, post.author.status)
        assertTrue(profile.status!!.isActive(Instant.parse("2026-10-08T04:59:59Z")))
        assertFalse(profile.status.isActive(Instant.parse("2026-10-08T05:00:00Z")))
        assertFalse(profile.status.isActive(Instant.parse("2026-10-08T05:01:00Z")))
        val indefinite = DiscourseParser.member(JSONObject("""{"status":{"emoji":"bili_118","description":" ","ends_at":null}}"""))
        assertTrue(indefinite.status!!.isActive())
    }

    @Test fun malformedFieldsNeverBecomeImageUrlsOrPermanentStatuses() {
        val member = DiscourseParser.member(JSONObject("""{"animated_avatar":{"url":"bad"},"flair_url":null,"status":{"emoji":[],"description":"bad"}}"""))
        assertEquals("", member.animatedAvatar)
        assertNull(member.flair)
        assertNull(member.status)
        assertNull(DiscourseParser.member(JSONObject("""{"status":{"emoji":"coffee","ends_at":"invalid"}}""")).status)
        assertNull(DiscourseParser.member(JSONObject("""{"status":{"emoji":"coffee","ends_at":123}}""")).status)
        assertNull(DiscourseParser.member(JSONObject("""{"flair_url":"  ","status":null}""")).flair)
    }

    @Test fun absentStatusDistinguishesSummaryFromCompleteProfile() {
        val json = JSONObject("""{"id":7,"username":"sample"}""")
        assertFalse(DiscourseParser.member(json).avatarDetailsLoaded)
        assertTrue(DiscourseParser.member(json, avatarDetailsLoaded = true).avatarDetailsLoaded)
        assertTrue(DiscourseParser.member(JSONObject("""{"id":7,"status":null}""")).avatarDetailsLoaded)
    }

    @Test fun emojiDirectoryIncludesCustomUploadsAndIgnoresMalformedEntries() {
        val json = JSONObject("""{"symbols":[{"name":"lollipop","url":"/images/emoji/twemoji/lollipop.png?v=15"}],"b站":[{"name":"bili_118","url":"//cdn.example/custom.png"},null,{"name":"bad","url":{}},{"name":"","url":"/wrong"}],"unknown":null}""")
        val groups = DiscourseParser.emojiGroups(json)
        val urls = DiscourseParser.emojiUrls(json)
        assertEquals(mapOf("lollipop" to "/images/emoji/twemoji/lollipop.png?v=15", "bili_118" to "//cdn.example/custom.png"), urls)
        assertEquals(setOf("symbols", "b站"), groups.map { it.name }.toSet())
        assertEquals("b站", groups.first { it.name == "b站" }.items.single().group)
    }

    @Test fun emojiDirectoryRemovesRepeatedNamesWithinEachGroupAndKeepsTheFirstUrl() {
        val json = JSONObject("""{"custom":[{"name":"wave","url":"/first.png"},{"name":"wave","url":"/later.png"},{"name":"wave_alias","url":"/first.png"}]}""")
        val items = DiscourseParser.emojiGroups(json).single().items
        assertEquals(listOf("wave", "wave_alias"), items.map { it.name })
        assertEquals(listOf("/first.png", "/first.png"), items.map { it.url })
    }

    @Test fun emojiDirectoryKeepsSameNamesInDifferentGroupsAndDistinctAliases() {
        val json = JSONObject("""{"standard":[{"name":"wave","url":"/standard.png"},{"name":"hello","url":"/standard.png"}],"custom":[{"name":"wave","url":"/custom.png"}]}""")
        val groups = DiscourseParser.emojiGroups(json).associateBy { it.name }
        assertEquals(listOf("wave", "hello"), groups.getValue("standard").items.map { it.name })
        assertEquals("/custom.png", groups.getValue("custom").items.single().url)
        assertEquals("custom", groups.getValue("custom").items.single().group)
    }
}
