package com.kudo.android

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.PrivateMessageScreen
import com.kudo.android.ui.editor.EditorScreen
import com.kudo.android.ui.editor.EditorViewModel
import com.kudo.android.ui.editor.ReplyEditorSheet
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PrivateMessageReplyUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val userId = Int.MIN_VALUE
    private val draftKey = "${userId}_702_1"
    private val requests = CopyOnWriteArrayList<String>()
    private val missingRules = AtomicBoolean()
    private val writes = AtomicInteger()
    private lateinit var app: AppContainer
    private lateinit var repository: CommunityRepository
    private lateinit var http: OkHttpClient
    private var editor: EditorViewModel? = null

    @Before fun fixture() {
        instrumentation.runOnMainSync { app = AppContainer(instrumentation.targetContext) }
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            requests += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/t/702.json" -> """{"id":702,"title":"discbot","archetype":"private_message","category_id":null,"post_stream":{"posts":[],"stream":[]}}"""
                "/latest" -> {
                    val settings = JSONObject().put("min_post_length", 16).put("max_post_length", 64000)
                    if (!missingRules.get()) settings.put("min_personal_message_post_length", 3)
                    """<script id="data-preloaded" type="application/json">${JSONObject().put("siteSettings", settings)}</script>"""
                }
                "/site.json" -> """{"categories":[]}"""
                else -> error("Unexpected private message fixture endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/html".toMediaType())).build()
        }.build()
        repository = CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }, http = http))
    }

    @After fun clean() {
        instrumentation.runOnMainSync {
            editor?.viewModelScope?.cancel()
            app.settings.clearDraft(draftKey)
            app.settings.clearDraft("${userId}_message_discobot")
        }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun showReply(raw: String = ""): EditorViewModel {
        lateinit var vm: EditorViewModel
        instrumentation.runOnMainSync {
            vm = EditorViewModel(app, userId, 702, 1,
                publishRequest = { writes.incrementAndGet(); PublishResult(702, false) }, categoryId = 0,
                rulesRequest = { repository.replyRules(702, 0) })
            editor = vm
            vm.edit(Draft(raw = raw))
        }
        compose.setContent { KudoTheme(DisplaySettings()) {
            ReplyEditorSheet(remember { MutableTransitionState(true) }, vm.sending, {}) {
                EditorScreen(vm, emptyList(), null, {}, {}, replyAuthor = Member(1, "discobot", "", ""))
            }
        } }
        compose.waitUntil(5000) { !vm.rulesLoading }
        return vm
    }

    @Test fun privateReplyUsesItsOwnMinimumAndOnlyPublishesAfterAnExplicitValidTap() {
        val vm = showReply()
        compose.onNodeWithText("暂时无法读取当前分类回帖规则").assertDoesNotExist()
        compose.onNodeWithTag("reply-length").assertTextEquals("0 / 3 字")
        compose.onNodeWithTag("editor-body").performTextInput("回答")
        compose.onNodeWithTag("reply-length").assertTextEquals("2 / 3 字")
        compose.onNodeWithText("发布").assertIsNotEnabled()
        compose.runOnIdle { vm.publish(); assertEquals(0, writes.get()) }
        compose.onNodeWithTag("editor-body").performTextInput("了")
        compose.onNodeWithTag("reply-length").assertTextEquals("3 字")
        compose.onNodeWithText("发布").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, writes.get()); assertEquals("回答了", vm.draft.raw) }
        compose.onNodeWithText("发布").performClick()
        compose.waitUntil(5000) { writes.get() == 1 && vm.draft.raw.isEmpty() }
        compose.runOnIdle {
            assertEquals("", app.settings.draft(draftKey).raw)
            assertEquals(listOf("/t/702.json", "/latest"), requests.toList())
        }
    }

    @Test fun unavailablePrivateRulesKeepTheReplyAndRetryWithoutSendingIt() {
        missingRules.set(true)
        val raw = "保留这条未发送的私信回复"
        val vm = showReply(raw)
        compose.onNodeWithTag("reply-length").assertTextEquals("回帖字数规则读取失败")
        compose.onNodeWithText("发布").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(raw, vm.draft.raw); assertEquals(0, writes.get()) }
        missingRules.set(false)
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(5000) { vm.replyRules != null }
        compose.onNodeWithText("发布").assertIsEnabled()
        compose.onNodeWithTag("editor-body").assertTextEquals(raw)
        compose.runOnIdle {
            assertEquals(raw, app.settings.draft(draftKey).raw)
            assertEquals(0, writes.get())
            assertEquals(listOf("/t/702.json", "/latest", "/t/702.json", "/latest"), requests.toList())
        }
    }

    @Test fun newPrivateMessageStillReadsItsRulesWithoutQueryingAnyTopicOrCategory() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            PrivateMessageScreen(Member(userId, "fixture", "", ""), repository, app.settings, {}, {}, recipient = "discobot")
        } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("0 / 64000").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("发送").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("/latest"), requests.toList()); assertEquals(0, writes.get()) }
    }
}
