package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.*
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MemberAccountUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val writes = AtomicInteger()
    private lateinit var http: OkHttpClient
    private lateinit var repository: CommunityRepository
    private var vm: AccountViewModel? = null
    private var noticesFixture: String? = null
    @Before fun fixture() {
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method != "GET") writes.incrementAndGet()
            val body = when (request.url.encodedPath) {
                "/session/current.json" -> """{"current_user":{"id":42,"username":"fixture","name":"资料测试","trust_level":3}}"""
                "/u/fixture.json" -> """{"user":{"id":42,"username":"fixture","name":"资料测试","trust_level":3}}"""
                "/u/fixture/summary.json" -> """{"user_summary":{"days_visited":825,"posts_read_count":357012,"likes_received":4000,"post_count":3102}}"""
                "/session/csrf" -> """{"csrf":"offline-csrf"}"""
                "/notifications/mark-read" -> "{}"
                "/notifications" -> noticesFixture ?: if (request.url.queryParameter("offset") == "30")
                    """{"notifications":[{"id":31,"notification_type":25,"data":{"title":"后页回应","display_username":"fixture"}}],"total_rows_notifications":31}"""
                    else """{"notifications":[${(1..30).joinToString(",") { """{"id":$it,"notification_type":2,"data":{"title":"回复夹具$it","display_username":"fixture"}}""" }}],"total_rows_notifications":31,"load_more_notifications":"/notifications?offset=30&limit=30"}"""
                else -> error("Unexpected fixture endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository = CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) {}
        }, "https://member.test/".toHttpUrl(), http = http))
    }
    @After fun release() { vm?.viewModelScope?.cancel(); http.dispatcher.executorService.shutdown() }

    @Test fun memberUsesRealStatisticsAndCompactWorkingEntrancesWithoutDuplicateHeaderActions() {
        val destinations = mutableListOf<AccountDestination>()
        var profiles = 0
        compose.setContent {
            KudoTheme(DisplaySettings()) {
                ProfileScreen(Member(42, "fixture", "资料测试", "", trustLevel = 3), {}, {}, {}, { profiles++ },
                    repository = repository, destination = destinations::add)
            }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("825").fetchSemanticsNodes().isNotEmpty() }
        for (text in listOf("访问天数", "已读帖子", "获赞", "发帖数", "35.7万", "4000", "3102")) compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithText("我的").assertDoesNotExist()
        compose.onNodeWithContentDescription("设置").assertDoesNotExist()
        compose.onNodeWithText("查看个人主页  →").assertDoesNotExist()
        capture("member-profile-light.png", "member-profile")
        compose.onNodeWithTag("member-avatar").performClick()
        compose.onNodeWithText("Lv.3").performClick()
        compose.onNodeWithText("我的点赞").performClick()
        compose.onNodeWithText("我的草稿").assertDoesNotExist()
        compose.onNodeWithText("邀请链接").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, profiles); assertEquals(listOf(AccountDestination.TRUST, AccountDestination.LIKES), destinations); assertEquals(0, writes.get()) }
    }
    @Test fun likesGroupFindsReactionOnFollowingPageWithoutMarkingRealOrFixtureNotificationsRead() {
        vm = AccountViewModel(repository, {}, {})
        val account = vm!!
        compose.setContent { KudoTheme(DisplaySettings()) { NotificationsScreen(account, {}, { _, _ -> }) } }
        compose.waitUntil(5000) { account.user != null && account.noticesLoaded }
        compose.onNodeWithText("赞", useUnmergedTree = true).performClick()
        compose.waitUntil(5000) { account.notices.any { it.id == 31 } }
        compose.onNodeWithText("后页回应").assertIsDisplayed()
        compose.onNodeWithText("回复夹具1").assertIsNotDisplayed()
        capture("member-notifications-likes.png", "member-notifications")
        compose.runOnIdle { assertEquals(0, writes.get()) }
    }
    @Test fun notificationsMaterialSurvivesSwitchingIntoThePageWithoutSamplingItsToolbar() {
        noticesFixture = """{"notifications":[{"id":1,"notification_type":2,"read":false,"data":{"title":"消息材质回归夹具","display_username":"fixture"}}],"total_rows_notifications":1}"""
        vm = AccountViewModel(repository, {}, {})
        val account = vm!!
        var visible by mutableStateOf(false)
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.DARK, glass = GlassMode.LIQUID))
        compose.setContent { KudoTheme(settings) {
            if (visible) NotificationsScreen(account, {}, { _, _ -> })
            else Box(Modifier.fillMaxSize())
        } }
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            for (glass in GlassMode.entries) {
                compose.runOnIdle { settings = settings.copy(theme = theme, glass = glass); visible = true }
                compose.waitUntil(5000) { account.user != null && account.noticesLoaded }
                compose.onNodeWithTag("notifications-toolbar-material").assertIsDisplayed()
                    .assert(!hasAnyAncestor(hasTestTag("notifications-content-backdrop")))
                val toolbar = compose.onNodeWithTag("notifications-toolbar-material").fetchSemanticsNode().boundsInRoot
                val content = compose.onNodeWithText("消息材质回归夹具").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(content.top >= toolbar.bottom)
                val tabs = compose.onNodeWithText("全部").fetchSemanticsNode().boundsInRoot
                val refresh = compose.onNodeWithContentDescription("刷新通知").fetchSemanticsNode().boundsInRoot
                val read = compose.onNodeWithContentDescription("全部标为已读").fetchSemanticsNode().boundsInRoot
                assertEquals(tabs.center.y, refresh.center.y, 1f)
                assertEquals(refresh.center.y, read.center.y, 1f)
                compose.onNodeWithText("消息", substring = false).assertDoesNotExist()
                compose.onNodeWithText("回复", useUnmergedTree = true).performClick()
                compose.onNodeWithTag("notice-group-REPLIES").assertIsDisplayed()
                compose.onNodeWithText("消息材质回归夹具").assertIsDisplayed()
                compose.onRoot().captureToImage()
                compose.runOnIdle { assertEquals(0, writes.get()); visible = false }
            }
        }
    }

    @Test fun darkLargeTextKeepsAllFourQuickEntrancesReachable() {
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            Box(Modifier.fillMaxSize()) { Box(Modifier.width(280.dp).height(480.dp)) {
                ProfileScreen(Member(42, "fixture", "较长的昵称用于验证文字布局", "", trustLevel = 2), {}, {}, {}, {})
            } }
        } }
        for (label in listOf("话题/回复", "我的收藏", "我的点赞", "浏览历史", "设置", "关于酷DO")) {
            compose.onNodeWithTag("member-profile").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithText("邀请链接").assertDoesNotExist()
    }

    @Test fun solvedNoticeShowsTopicAndCheckmarkWithoutConfusingUnreadOrOtherCustomEvents() {
        noticesFixture = """{"notifications":[
            {"id":101,"notification_type":14,"topic_id":3003776,"post_number":34,"read":false,"data":{"message":"solved.topic_solved_notification","display_username":"3023599024","topic_title":"新来的前端女同事，怎么深入交流？","title":"solved.notification.topic_solved_title"}},
            {"id":102,"notification_type":14,"read":true,"data":{"message":"solved.topic_solved_notification","topic_title":"另一条已读解决通知"}},
            {"id":103,"notification_type":14,"read":true,"data":{"message":"another.plugin_event","title":"普通插件通知"}}
        ],"total_rows_notifications":3}"""
        vm = AccountViewModel(repository, {}, {})
        val account = vm!!
        var target: Pair<Int, Int?>? = null
        compose.setContent { KudoTheme(DisplaySettings()) { NotificationsScreen(account, {}, { topic, post -> target = topic to post }) } }
        compose.waitUntil(5000) { account.noticesLoaded }
        compose.onNodeWithTag("notifications-toolbar-material").assertIsDisplayed()
        compose.onNodeWithText("全部").assertIsDisplayed()
        compose.onNodeWithText("消息", substring = false).assertDoesNotExist()
        val tabs = compose.onNodeWithText("全部").fetchSemanticsNode().boundsInRoot
        val refresh = compose.onNodeWithContentDescription("刷新通知").fetchSemanticsNode().boundsInRoot
        val read = compose.onNodeWithContentDescription("全部标为已读").fetchSemanticsNode().boundsInRoot
        assertEquals(tabs.center.y, refresh.center.y, 1f)
        assertEquals(refresh.center.y, read.center.y, 1f)
        compose.onNodeWithText("新来的前端女同事，怎么深入交流？").assertIsDisplayed()
        compose.onNodeWithText("3023599024 将话题标记为已解决").assertIsDisplayed()
        for (id in listOf(101, 102)) compose.onNodeWithTag("notice-solved-$id", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("notice-unread-101", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("notice-unread-102", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("notice-solved-103", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("solved.notification.topic_solved_title").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, writes.get()) }
        capture("solved-notice-fixed.png", "member-notifications")
        compose.onNodeWithText("新来的前端女同事，怎么深入交流？").performClick()
        compose.runOnIdle { assertEquals(3003776 to 34, target) }
        compose.waitUntil(5000) { account.notices.first().read }
        compose.onNodeWithTag("notice-unread-101", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("notice-solved-101", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, writes.get()) }
    }

    private fun capture(name: String, tag: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
