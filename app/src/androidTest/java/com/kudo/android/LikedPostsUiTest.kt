package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.*
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class LikedPostsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = mutableListOf<ViewModel>()
    private val requests = CopyOnWriteArrayList<Request>()
    private val firstPages = AtomicInteger()
    private val morePages = AtomicInteger()
    private lateinit var http: OkHttpClient
    private lateinit var repository: CommunityRepository

    private fun action(id: Int, floor: Int, title: String) =
        """{"id":$id,"topic_id":99,"post_number":$floor,"title":"$title","excerpt":"这条赞对应原回复内容","action_type":1}"""

    @Before fun fixture() {
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            var status = 200
            val body = when (request.url.encodedPath) {
                "/user_actions.json" -> if (request.url.queryParameter("offset") == "30") {
                    if (morePages.incrementAndGet() == 1) { status = 500; "{}" }
                    else """{"user_actions":[${action(31, 9, "后页原楼层")},${action(1, 8, "点赞原楼层")}]}"""
                } else if (firstPages.incrementAndGet() == 1) {
                    """{"user_actions":[${List(30) { index -> action(index + 1, index + 8, if (index == 0) "点赞原楼层" else "点赞条目$index") }.joinToString(",")}]}"""
                } else """{"user_actions":[${action(3, 10, "刷新后的点赞")}]}"""
                "/topics/created-by/current-member.json", "/read.json", "/topics/private-messages/current-member.json" ->
                    """{"topic_list":{"topics":[${(1..30).joinToString(",") { """{"id":$it,"title":"自己的话题$it","posts_count":1,"category_id":0}""" }}]}}"""
                "/drafts.json" -> """{"drafts":[],"has_more":false}"""
                else -> error("Unexpected endpoint ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        repository = CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) {}
        }, "https://likes.test/".toHttpUrl(), http = http))
    }

    @After fun release() {
        models.forEach { it.viewModelScope.cancel() }
        http.dispatcher.executorService.shutdown()
    }

    @Test fun givenLikesRefreshPaginateRetryAndOpenOriginalFloorWithoutWriting() {
        val vm = LikedPostsViewModel(repository, "current-member").also(models::add)
        var opened = ""
        compose.setContent { KudoTheme(DisplaySettings()) {
            val state by vm.state.collectAsState()
            LikedPostsScreen(state, {}, {}, { opened = it }, { vm.load() }, { vm.load(true) }, vm::retry)
        } }
        compose.waitUntil(5000) { vm.state.value.loaded }
        compose.onNodeWithText("我的点赞").assertIsDisplayed()
        capture("liked-posts-first-page.png", "liked-posts")
        compose.onNodeWithText("点赞原楼层").performClick()
        compose.runOnIdle { assertEquals("https://linux.do/t/topic/99/8", opened) }
        compose.onNodeWithTag("liked-posts-list").performScrollToIndex(24)
        compose.onNodeWithTag("liked-posts-list").performTouchInput { swipeUp() }
        compose.waitUntil(5000) { vm.state.value.error != null }
        compose.onNodeWithTag("liked-posts-list").performScrollToNode(hasText("重新加载"))
        compose.onNodeWithText("重新加载").performClick()
        compose.waitUntil(5000) { vm.state.value.entries.size == 31 }
        compose.onNodeWithText("后页原楼层").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("https://linux.do/t/topic/99/9", opened) }
        compose.onNodeWithContentDescription("刷新").performClick()
        compose.waitUntil(5000) { vm.state.value.entries.singleOrNull()?.number == 10 }
        compose.onNodeWithText("刷新后的点赞").assertIsDisplayed()
        compose.onNodeWithText("点赞原楼层").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("0", "30", "30", "0"), requests.map { it.url.queryParameter("offset") })
            requests.forEach {
                assertEquals("GET", it.method)
                assertEquals("current-member", it.url.queryParameter("username"))
                assertEquals("1", it.url.queryParameter("filter"))
            }
        }
    }

    @Test fun emptyListAndExpiredSessionProvideRefreshAndLoginRecovery() {
        var state by mutableStateOf(ProfileFeedState(loaded = true))
        var logins = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            LikedPostsScreen(state, {}, { logins++ }, {},
                { state = state.copy(error = ApiFailure(FailureKind.SIGN_IN, "会话过期")) }, {}, {})
        } }
        compose.onNodeWithText("暂无点赞").assertIsDisplayed()
        compose.onNodeWithContentDescription("刷新").performClick()
        compose.onNodeWithText("暂无点赞").assertDoesNotExist()
        compose.onNodeWithText("登录 Linux.do").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, logins) }
        assertTrue(requests.isEmpty())
    }

    @Test fun darkNarrowLargeTextKeepsEntryFloorAndPagingActionReachable() {
        var more = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            Box(Modifier.width(280.dp).height(480.dp)) {
                LikedPostsScreen(ProfileFeedState(entries = listOf(ProfileEntry("long", 99, 128,
                    "这是一条较长的点赞主题标题用于验证窄屏大字号下的正常截断与楼层跳转", "较长的正文摘要不会遮住底部继续加载入口。")),
                    next = "30", loaded = true), {}, {}, {}, {}, { more++ }, {})
            }
        } }
        compose.onNodeWithText("我的点赞").assertIsDisplayed()
        compose.onNodeWithContentDescription("刷新").assertIsDisplayed()
        compose.onNodeWithTag("profile-entry-long").assertIsDisplayed()
        capture("liked-posts-dark-narrow.png", "liked-posts")
        compose.runOnIdle { assertEquals(0, more) }
    }

    @Test fun topicsDraftActionOpensExistingDraftsAndReturnsWithReadingPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("liked_posts_draft_fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val fixtureContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = prefs
            override fun getApplicationContext(): Context = this
        }
        val settings = AppSettings(fixtureContext)
        settings.saveDraft("42_new_0", Draft("本地草稿", "原有草稿内容", 7))
        val topicVm = PersonalTopicsViewModel(repository, "current-member", AccountDestination.TOPICS).also(models::add)
        var destination by mutableStateOf(AccountDestination.TOPICS)
        try {
            compose.setContent { KudoTheme(DisplaySettings()) {
                val nav = rememberNavController()
                NavHost(nav, "topics") {
                    composable("topics") {
                        val vm = if (destination == AccountDestination.TOPICS) topicVm else remember(destination) {
                            PersonalTopicsViewModel(repository, "current-member", destination).also(models::add)
                        }
                        PersonalTopicsScreen(vm, emptyList(), TopicListStyle.TITLE, {}, {}, { _, _ -> }, {}, {},
                            drafts = { nav.navigate("drafts") })
                    }
                    composable("drafts") {
                        DraftsScreen(Member(42, "current-member", "", ""), repository, settings,
                            { nav.popBackStack() }, {}, {})
                    }
                }
            } }
            compose.waitUntil(5000) { !topicVm.loading && topicVm.topics.isNotEmpty() }
            compose.onNodeWithTag("personal-list-TOPICS").performScrollToIndex(20)
            compose.onNodeWithTag("topic-title-21").assertIsDisplayed()
            val draftBounds = compose.onNodeWithContentDescription("我的草稿").fetchSemanticsNode().boundsInRoot
            val refreshBounds = compose.onNodeWithContentDescription("刷新").fetchSemanticsNode().boundsInRoot
            assertEquals(refreshBounds.center.y, draftBounds.center.y, 1f)
            assertTrue(draftBounds.right <= refreshBounds.left)
            capture("topics-draft-entry.png")
            compose.onNodeWithContentDescription("我的草稿").performClick()
            compose.onNodeWithText("本地草稿").assertIsDisplayed()
            compose.onNodeWithText("原有草稿内容").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("topic-title-21").assertIsDisplayed()
            compose.runOnIdle { destination = AccountDestination.HISTORY }
            compose.onNodeWithContentDescription("我的草稿").assertDoesNotExist()
            compose.runOnIdle { destination = AccountDestination.MESSAGES }
            compose.onNodeWithContentDescription("我的草稿").assertDoesNotExist()
            compose.onNodeWithContentDescription("写私信").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1, requests.count { it.url.encodedPath == "/topics/created-by/current-member.json" })
                assertTrue(requests.all { it.method == "GET" })
                assertEquals("原有草稿内容", settings.draft("42_new_0").raw)
            }
        } finally { prefs.edit().clear().commit() }
    }

    private fun capture(name: String, tag: String? = null) {
        val node = if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)
        val bitmap = node.captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
