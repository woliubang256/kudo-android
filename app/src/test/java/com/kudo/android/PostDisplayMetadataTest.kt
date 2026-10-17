package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PostDisplayMetadataTest {
    private fun post(number: Int, extra: String = "") = DiscourseParser.post(JSONObject(
        """{"id":$number,"post_number":$number,"user_id":8,"username":"member"$extra}"""))

    @Test fun grantedBadgesTitleAndModeratorComeFromThePostNotTrustLevel() {
        val parsed = post(2, """, "user_title":"&lt;开发者&gt;", "moderator":true, "trust_level":4,
            "badges_granted":[{"badge":{"id":4,"name":"小芽","description":"初次参与","icon":"seedling","badge_type_id":3}},
            {"badge":{"id":4,"name":"重复"}}, {"badge":{"id":5,"name":"观察者","icon":"far-eye","image_url":"/uploads/badge.png"}},null,{"badge":{"id":0}}]""")
        assertEquals("<开发者>", parsed.author.title)
        assertTrue(parsed.author.moderator)
        assertEquals(listOf(4, 5), parsed.author.headerBadges.map { it.id })
        assertEquals("far-eye", parsed.author.headerBadges.last().icon)
        assertEquals("/uploads/badge.png", parsed.author.headerBadges.last().image)
        assertTrue(post(3, """, "trust_level":4""").author.headerBadges.isEmpty())
        assertEquals("", post(4).author.title)
    }

    @Test fun metadataEnrichmentCannotReplacePostGrantedBadgesOrExplicitTitle() = runTest {
        val original = post(2, """, "user_title":"帖子头衔", "badges_granted":[{"badge":{"id":4,"name":"小芽","icon":"seedling"}}]""").author
            .copy(avatarDetailsLoaded = false)
        val cache = AvatarMetadataCache(this, { listOf(original.copy(title = "资料头衔", moderator = true,
            headerBadges = listOf(PostHeaderBadge(9, "其他徽章", "", "eye", "", 1)))) }, { emptyMap() })
        try {
            val resolved = cache.resolve(original)
            assertEquals("帖子头衔", resolved.title)
            assertTrue(resolved.moderator)
            assertEquals(original.headerBadges, resolved.headerBadges)
        } finally { cache.close() }
    }

    @Test fun systemActionsAreTypedAndDoNotDependOnFloorOrEmptyBody() {
        val action = post(7, """, "post_type":3, "action_code":"pinned_globally.enabled", "action_code_who":"目标"""")
        assertTrue(action.isSystemAction)
        assertEquals("全站置顶了此话题", postActionDescription(action.actionCode))
        assertEquals("目标", action.actionWho)
        assertEquals("取消了此话题的全站置顶", postActionDescription("pinned_globally.disabled"))
        assertFalse(post(2).isSystemAction)
        assertFalse(post(3, """, "post_type":1, "action_code":"pinned.enabled"""").isSystemAction)
        assertEquals("更新了话题设置", postActionDescription("future.action"))
    }

    @Test fun actionRecordsAreIndependentTimelineItemsNotReplyParents() {
        val action = post(2, """, "post_type":3, "reply_to_post_number":88""")
        val normal = post(3, """, "reply_to_post_number":2""")
        val child = post(4, """, "reply_to_post_number":3""")
        val threads = postThreads(listOf(post(1), action, normal, child))
        assertTrue(missingReplyParents(listOf(action, normal, child)).isEmpty())
        assertEquals(listOf(1, 2, 3), threads.map { it.root.number })
        assertTrue(threads[1].replies.isEmpty())
        assertEquals(listOf(4), threads[2].replies.map(Post::number))
    }
}
