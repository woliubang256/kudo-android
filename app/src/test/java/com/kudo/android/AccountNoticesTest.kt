package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountNoticesTest {
    @Test fun badgeNotificationsRetainOnlyValidBadgeIdsWithoutLosingOtherTargets() {
        val notices = DiscourseParser.notices(JSONObject("""{"notifications":[
            {"id":1,"notification_type":12,"topic_id":42,"post_number":3,"data":{"badge_id":7,"badge_name":"首次引用","url":"/badges/7/quote"}},
            {"id":2,"notification_type":12,"data":{"badge_id":0}},
            {"id":3,"notification_type":12,"data":{"badge_id":-1}},
            {"id":4,"notification_type":12,"data":{"badge_id":"invalid"}},
            {"id":5,"notification_type":12,"data":{"badge_id":null}},
            {"id":6,"notification_type":12,"data":{}},
            {"id":7,"notification_type":2,"topic_id":99,"data":{"badge_id":7,"title":"普通回复"}}
        ]}"""))
        assertEquals(7, notices.first().badgeId)
        assertEquals("首次引用", notices.first().title)
        assertEquals("/badges/7/quote", notices.first().url)
        assertEquals(42, notices.first().topicId)
        assertEquals(3, notices.first().postNumber)
        assertTrue(notices.drop(1).all { it.badgeId == null })
        assertEquals(99, notices.last().topicId)
    }

    @Test fun solvedNotificationUsesTheRealTopicTitleInsteadOfTheTranslationKey() {
        val notice = DiscourseParser.notices(JSONObject("""{"notifications":[{"id":685498710,"notification_type":14,"topic_id":3003776,"post_number":34,"read":true,"fancy_title":"新来的前端女同事，怎么深入交流？","data":{"message":"solved.topic_solved_notification","display_username":"3023599024","topic_title":"新来的前端女同事，怎么深入交流？","title":"solved.notification.topic_solved_title"}}]}""")).single()
        assertEquals("新来的前端女同事，怎么深入交流？", notice.title)
        assertEquals("3023599024 将话题标记为已解决", notice.description)
        assertEquals(3003776, notice.topicId)
        assertEquals(34, notice.postNumber)
        assertTrue(notice.read)
        assertTrue(notice.isSolved)
    }

    @Test fun solvedTitleFallsBackWithoutShowingPluginKeysAndDecodesEntities() {
        fun parse(data: String, fancy: String = "") = DiscourseParser.notices(JSONObject("""{"notifications":[{"id":1,"notification_type":14,"fancy_title":"$fancy","data":{"message":"solved.topic_solved_notification","title":"solved.notification.topic_solved_title"$data}}]}""")).single()
        assertEquals("A & B", parse(",\"topic_title\":\"A &amp; B\"", "次选标题").title)
        assertEquals("真实主题", parse("", "<b>真实主题</b>").title)
        assertEquals("回退主题", parse(",\"topic_title\":\"<p></p>\"", "回退主题").title)
        val missing = parse("")
        assertEquals("话题已解决", missing.title)
        assertEquals("话题已解决", missing.description)
        assertTrue(missing.isSolved)
    }

    @Test fun genericCustomNotificationsAndWrongTypesAreNeverMarkedSolved() {
        val notices = DiscourseParser.notices(JSONObject("""{"notifications":[
            {"id":1,"notification_type":14,"data":{"message":"another.plugin_event","title":"插件通知","topic_title":"关联主题"}},
            {"id":2,"notification_type":14,"data":{"title":"普通自定义通知"}},
            {"id":3,"notification_type":2,"data":{"message":"solved.topic_solved_notification","title":"回复主题"}},
            {"id":4,"notification_type":12,"data":{"badge_name":"新徽章","badge_id":2}}
        ]}"""))
        assertTrue(notices.none { it.isSolved })
        assertEquals(listOf("插件通知", "普通自定义通知", "回复主题", "新徽章"), notices.map { it.title })
        assertEquals("有新的动态", notices.first().description)
        assertEquals("https://linux.do/badges/2", notices.last().url)
    }

    @Test fun websiteGroupsKeepMentionsLikesReactionsAndPrivateMessagesSeparate() {
        fun notice(type: Int) = DiscourseParser.notices(JSONObject("""{"notifications":[{"id":1,"notification_type":$type,"data":{"display_username":"member","title":"主题"}}]}""")).single()
        assertTrue(NoticeGroup.REPLIES.includes(notice(1)))
        assertTrue(NoticeGroup.REPLIES.includes(notice(15)))
        assertTrue(NoticeGroup.LIKES.includes(notice(19)))
        assertTrue(NoticeGroup.LIKES.includes(notice(25)))
        assertTrue(NoticeGroup.MESSAGES.includes(notice(7)))
        assertFalse(NoticeGroup.MESSAGES.includes(notice(30)))
        assertFalse(NoticeGroup.LIKES.includes(notice(12)))
        assertTrue(NoticeGroup.ALL.includes(notice(999)))
        assertEquals("member", notice(2).actor?.username)
    }
    @Test fun inaccessibleRowsDoNotPrematurelyEndPaginationAndRecentModeIsNeverUsed() = runBlocking {
        val server = MockWebServer()
        val cookies = object : SessionCookies { override fun get(url: String) = ""; override fun set(url: String, cookie: String) {} }
        try {
            val repository = CommunityRepository(DiscourseClient(cookies, server.url("/")))
            server.enqueue(MockResponse().setBody("""{"notifications":[],"total_rows_notifications":31,"load_more_notifications":"/notifications?offset=30&limit=30"}"""))
            val first = repository.noticePage(0)
            assertNotNull(first.next)
            assertEquals("/notifications?offset=0&limit=30", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"notifications":[{"id":2,"notification_type":25,"data":{}}],"total_rows_notifications":31,"load_more_notifications":"/notifications?offset=60&limit=30"}"""))
            val last = repository.noticePage(30, first.next)
            assertNull(last.next)
            assertTrue(NoticeGroup.LIKES.includes(last.notices.single()))
            assertEquals("/notifications?offset=30&limit=30", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
