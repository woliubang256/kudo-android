package com.kudo.android

import android.content.Context
import android.webkit.WebView
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.VerificationHost
import com.kudo.android.ui.editor.EditorScreen
import com.kudo.android.ui.editor.EditorViewModel
import com.kudo.android.ui.editor.ReplyEditorSheet
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EditorVerificationUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: AppContainer
    private lateinit var vm: EditorViewModel
    private val rulesReady = AtomicBoolean()
    private val nativeReady = AtomicBoolean(true)
    private val ruleReads = AtomicInteger()
    private val nativeChecks = AtomicInteger()
    private val writes = AtomicInteger()
    private val loads = mutableListOf<String>()
    private val draftKey = "${Int.MIN_VALUE}_701_8"
    private val raw = "完成网页验证后也必须保留的回复草稿"

    @After fun clean() {
        instrumentation.runOnMainSync {
            if (::vm.isInitialized) vm.viewModelScope.cancel()
            if (::app.isInitialized) app.settings.clearDraft(draftKey)
        }
    }

    private fun factory(context: Context): WebView = object : WebView(context) {
        override fun loadUrl(url: String) {
            loads += url
            loadDataWithBaseURL("https://verification-fixture.test/", "<html><body>验证夹具</body></html>", "text/html", "UTF-8", null)
        }
    }

    private fun show(error: Throwable = ApiFailure(FailureKind.VERIFICATION, CloudflareVerificationMessage)) {
        instrumentation.runOnMainSync {
            app = AppContainer(instrumentation.targetContext)
            vm = EditorViewModel(app, Int.MIN_VALUE, 701, 8,
                publishRequest = { writes.incrementAndGet(); PublishResult(701, false) },
                rulesRequest = {
                    ruleReads.incrementAndGet()
                    if (!rulesReady.get()) throw error
                    ReplyRules(4, 64000)
                })
            vm.edit(Draft(raw = raw))
        }
        runBlocking { withTimeout(5000) { while (vm.rulesLoading) delay(10) } }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("GET", chain.request().method)
            assertEquals("/latest.json", chain.request().url.encodedPath)
            nativeChecks.incrementAndGet()
            if (!nativeReady.get()) Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(403).message("Forbidden").header("cf-mitigated", "challenge")
                .body("<html>Just a moment...</html>".toResponseBody("text/html".toMediaType())).build()
            else Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"topic_list":{"topics":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }, http = http)
        compose.setContent { KudoTheme(DisplaySettings()) {
            VerificationHost(client, "fixture-agent", ::factory) {
                ReplyEditorSheet(remember { MutableTransitionState(true) }, vm.sending, {}) {
                    EditorScreen(vm, emptyList(), null, {}, {})
                }
            }
        } }
    }

    private fun assertDraftAndNoWrites() {
        compose.onNodeWithTag("editor-body").assertTextEquals(raw)
        compose.runOnIdle {
            assertEquals(raw, vm.draft.raw)
            assertEquals(raw, app.settings.draft(draftKey).raw)
            assertEquals(0, writes.get())
        }
    }

    @Test fun challengeOpensVerificationAndOnlyReloadsRulesAfterSuccessfulCheck() {
        show()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.onNodeWithText("正在读取回帖字数规则…").assertDoesNotExist()
        compose.onNodeWithText("发布").assertIsNotEnabled()
        compose.onNodeWithText("完成网页验证").assertIsDisplayed().performClick()
        compose.onNodeWithTag("verification-page").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, ruleReads.get()); assertEquals(0, nativeChecks.get()) }
        rulesReady.set(true)
        compose.onNodeWithTag("verification-complete").performClick()
        compose.waitUntil(5000) { vm.replyRules != null }
        compose.onNodeWithTag("verification-page").assertDoesNotExist()
        compose.onNodeWithText("完成网页验证").assertDoesNotExist()
        compose.onNodeWithText("发布").assertIsEnabled()
        assertDraftAndNoWrites()
        compose.runOnIdle {
            assertEquals(listOf("https://linux.do/challenge"), loads)
            assertEquals(1, nativeChecks.get())
            assertEquals(2, ruleReads.get())
        }
    }

    @Test fun cancelVerificationKeepsDraftAndDoesNotReloadRules() {
        show()
        compose.onNodeWithText("完成网页验证").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("verification-page").assertDoesNotExist()
        compose.onNodeWithText("完成网页验证").assertIsDisplayed()
        compose.onNodeWithText("发布").assertIsNotEnabled()
        assertDraftAndNoWrites()
        compose.runOnIdle { assertEquals(1, ruleReads.get()); assertEquals(0, nativeChecks.get()) }
    }

    @Test fun failedNativeCheckStaysInVerificationAndDoesNotReloadRules() {
        nativeReady.set(false)
        show()
        compose.onNodeWithText("完成网页验证").performClick()
        compose.onNodeWithTag("verification-complete").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(CloudflareVerificationMessage).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("verification-page").assertIsDisplayed()
        compose.onNodeWithTag("verification-complete").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, ruleReads.get()); assertEquals(0, writes.get()) }
        compose.onNodeWithContentDescription("返回").performClick()
        assertDraftAndNoWrites()
    }

    @Test fun rulesStillChallengedAfterVerificationKeepTheVerificationAction() {
        show()
        compose.onNodeWithText("完成网页验证").performClick()
        compose.onNodeWithTag("verification-complete").performClick()
        compose.waitUntil(5000) { ruleReads.get() == 2 && !vm.rulesLoading }
        compose.onNodeWithTag("verification-page").assertDoesNotExist()
        compose.onNodeWithText("完成网页验证").assertIsDisplayed()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.onNodeWithText("正在读取回帖字数规则…").assertDoesNotExist()
        compose.onNodeWithText("发布").assertIsNotEnabled()
        assertDraftAndNoWrites()
        compose.runOnIdle { assertEquals(2, ruleReads.get()) }
    }

    @Test fun ordinaryFailureUsesRetryEvenIfItsMessageMentionsVerification() {
        show(ApiFailure(FailureKind.NETWORK, CloudflareVerificationMessage))
        compose.onNodeWithText("完成网页验证").assertDoesNotExist()
        compose.onNodeWithText("正在读取回帖字数规则…").assertDoesNotExist()
        rulesReady.set(true)
        compose.onNodeWithText("重试").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { vm.replyRules != null }
        compose.onNodeWithText("发布").assertIsEnabled()
        assertDraftAndNoWrites()
        compose.runOnIdle {
            assertEquals(2, ruleReads.get())
            assertEquals(0, nativeChecks.get())
            assertTrue(loads.isEmpty())
        }
    }
}
