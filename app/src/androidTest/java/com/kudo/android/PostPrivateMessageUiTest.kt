package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.PrivateMessageScreen
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PostPrivateMessageUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val user = Member(Int.MIN_VALUE + 1, "message_fixture", "", "")
    private val author = Member(2, "author", "主帖作者", "")
    private val post = Post(10, 1, author, "<p>主帖</p>", "", 0, false, null, null, false)
    private val reply = post.copy(id = 81, number = 8, author = Member(3, "reply_author", "回复作者", ""))
    private val requests = CopyOnWriteArrayList<String>()
    private val permission = AtomicBoolean(true)
    private val delayProfile = AtomicBoolean()
    private val profileStarted = CountDownLatch(1)
    private val releaseProfile = CountDownLatch(1)
    private val profileFinished = CountDownLatch(1)
    private lateinit var settings: AppSettings
    private lateinit var repository: CommunityRepository
    private lateinit var http: OkHttpClient

    @Before fun fixture() {
        settings = AppSettings(instrumentation.targetContext)
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            requests += request.url.encodedPath
            val delayed = delayProfile.get() && request.url.encodedPath.startsWith("/u/")
            if (delayed) { profileStarted.countDown(); check(releaseProfile.await(5, TimeUnit.SECONDS)) }
            val body = when (request.url.encodedPath) {
                "/u/author.json", "/u/reply_author.json" -> """{"user":{"can_send_private_message_to_user":${permission.get()}}}"""
                "/t/5/1.json" -> """{"id":5,"title":"中文 & 标题？","post_stream":{"posts":[],"stream":[]}}"""
                "/latest" -> """<script id="data-preloaded" type="application/json">{"siteSettings":{"min_personal_message_post_length":3,"max_post_length":64000}}</script>"""
                else -> error("Unexpected post-message fixture endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/html".toMediaType())).build().also { if (delayed) profileFinished.countDown() }
        }.build()
        repository = CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }, http = http))
    }

    @After fun clean() {
        releaseProfile.countDown()
        settings.drafts(user.id).forEach { settings.clearDraft(it.key) }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun showFlow() {
        var target by mutableStateOf<Pair<String, PostMessageSource>?>(null)
        var restore by mutableStateOf("")
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicActionsHost(repository, user, GlassMode.TRANSLUCENT, {}, { _, _ -> }, {},
                composeMessage = { recipient, source -> target = recipient to source }) {
                val selected = target
                if (selected != null || restore.isNotBlank()) {
                    PrivateMessageScreen(user, repository, settings, { target = null; restore = "" },
                        { error("The fixture must not send private messages") }, recipient = selected?.first.orEmpty(),
                        source = selected?.second, existingDraftKey = restore)
                } else {
                    val actions = LocalTopicActions.current
                    Column {
                        Button({ actions.openDetail(5, post, {}) }) { Text("主帖菜单") }
                        Button({ actions.openReply(5, reply, {}) }) { Text("回复菜单") }
                        Button({ restore = privateMessageDraftKey(user.id, "author", PostMessageSource(5, 1, "中文 & 标题？")) }) { Text("恢复来源草稿") }
                    }
                }
            }
        } }
    }

    private fun openMessage(menu: String) {
        compose.onNodeWithText(menu).performClick()
        compose.onNodeWithText("私信").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 3 }
    }

    @Test fun mainAndReplyMessagesPrefillTheirOwnAuthorAndExactPostLinkWithoutSending() {
        showFlow()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        openMessage("主帖菜单")
        compose.onAllNodes(hasSetTextAction())[0].assertTextEquals("author")
        compose.onAllNodes(hasSetTextAction())[1].assertTextEquals("回复：中文 & 标题？")
        compose.onAllNodes(hasSetTextAction())[2].assertTextEquals("https://linux.do/t/topic/5/1")
        compose.runOnIdle {
            val saved = settings.drafts(user.id).single()
            assertEquals("https://linux.do/t/topic/5/1", saved.draft.raw)
            assertTrue(saved.privateMessage)
        }
        capture("post-private-message-main.png")
        compose.onNodeWithContentDescription("返回").performClick()
        openMessage("回复菜单")
        compose.onAllNodes(hasSetTextAction())[0].assertTextEquals("reply_author")
        compose.onAllNodes(hasSetTextAction())[1].assertTextEquals("回复：中文 & 标题？")
        compose.onAllNodes(hasSetTextAction())[2].assertTextEquals("https://linux.do/t/topic/5/8")
        compose.waitUntil(5000) { requests.count { it == "/latest" } == 2 }
        compose.runOnIdle {
            assertEquals(2, settings.drafts(user.id).size)
            assertEquals(listOf("/u/author.json", "/t/5/1.json", "/latest", "/u/reply_author.json", "/t/5/1.json", "/latest"), requests.toList())
        }
        capture("post-private-message-reply.png")
    }

    @Test fun editsSurviveReopeningAndDraftListRestoreWithoutOverwritingOrdinaryMessages() {
        val genericKey = privateMessageDraftKey(user.id, "author")
        val generic = Draft("普通私信标题", "普通私信正文", recipients = "author")
        settings.saveDraft(genericKey, generic)
        showFlow()
        openMessage("主帖菜单")
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("different_author")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("修改的标题")
        compose.onAllNodes(hasSetTextAction())[2].performTextReplacement("保留未发送内容")
        compose.onNodeWithContentDescription("返回").performClick()
        openMessage("主帖菜单")
        compose.onAllNodes(hasSetTextAction())[0].assertTextEquals("different_author")
        compose.onAllNodes(hasSetTextAction())[1].assertTextEquals("修改的标题")
        compose.onAllNodes(hasSetTextAction())[2].assertTextEquals("保留未发送内容")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("恢复来源草稿").performClick()
        compose.onAllNodes(hasSetTextAction())[0].assertTextEquals("different_author")
        compose.onAllNodes(hasSetTextAction())[1].assertTextEquals("修改的标题")
        compose.onAllNodes(hasSetTextAction())[2].assertTextEquals("保留未发送内容")
        compose.runOnIdle { assertEquals(generic, settings.draft(genericKey)) }
    }

    @Test fun guestIsSentToLoginAndOwnPostsHaveNoMessageAction() {
        var current by mutableStateOf<Member?>(null)
        var logins = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicActionsHost(repository, current, GlassMode.TRANSLUCENT, { logins++ }, { _, _ -> }, {}) {
                val actions = LocalTopicActions.current
                Button({ actions.openDetail(5, post, {}) }) { Text("打开菜单") }
            }
        } }
        compose.onNodeWithText("打开菜单").performClick()
        compose.onNodeWithText("私信").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, logins); assertTrue(requests.isEmpty()); current = author }
        compose.onNodeWithText("打开菜单").performClick()
        compose.onNodeWithText("私信").assertDoesNotExist()
    }

    @Test fun permissionErrorRetriesTheMessageActionRatherThanReloadingTheReply() {
        permission.set(false)
        showFlow()
        compose.onNodeWithText("回复菜单").performClick()
        compose.onNodeWithText("私信").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("暂时无法向该用户发送私信").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(listOf("/u/reply_author.json"), requests.toList()) }
        permission.set(true)
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 3 }
        compose.onAllNodes(hasSetTextAction())[0].assertTextEquals("reply_author")
        compose.onAllNodes(hasSetTextAction())[2].assertTextEquals("https://linux.do/t/topic/5/8")
    }

    @Test fun switchingAccountsCancelsPreparationAndCannotReplayTheOldEditor() {
        var current by mutableStateOf(user)
        var opens = 0
        delayProfile.set(true)
        compose.setContent { KudoTheme(DisplaySettings()) {
            TopicActionsHost(repository, current, GlassMode.TRANSLUCENT, {}, { _, _ -> }, {},
                composeMessage = { _, _ -> opens++ }) {
                val actions = LocalTopicActions.current
                Button({ actions.openDetail(5, post, {}) }) { Text("打开菜单") }
            }
        } }
        compose.onNodeWithText("打开菜单").performClick()
        compose.onNodeWithText("私信").performScrollTo().performClick()
        compose.waitUntil(5000) { profileStarted.count == 0L }
        compose.runOnIdle { current = user.copy(id = user.id + 1) }
        compose.waitForIdle()
        releaseProfile.countDown()
        compose.waitUntil(5000) { profileFinished.count == 0L }
        compose.runOnIdle { current = user }
        compose.onNodeWithText("打开菜单").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, opens); assertEquals(listOf("/u/author.json"), requests.toList()) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
