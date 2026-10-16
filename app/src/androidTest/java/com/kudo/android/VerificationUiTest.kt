package com.kudo.android

import android.content.Context
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kudo.android.data.*
import com.kudo.android.ui.account.*
import com.kudo.android.ui.components.ErrorState
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.CompletableDeferred
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VerificationUiTest {
    @get:Rule val compose = createComposeRule()
    private val loads = mutableListOf<String>()
    private lateinit var web: WebView
    private fun factory(context: Context): WebView = object : WebView(context) {
        override fun loadUrl(url: String) {
            loads += url
            loadDataWithBaseURL("https://verification-fixture.test/", "<html><body>该页面不存在或者是一个不公开页面</body></html>", "text/html", "UTF-8", null)
        }
    }.also { web = it }

    @Test fun missingPagePromptsMoreRefreshesAndRefreshReopensChallengeWithoutCache() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            VerificationScreen("fixture-agent", {}, {}, {}, ::factory)
        } }
        compose.onNodeWithText("如果页面显示不存在，请多刷新几次，直到出现验证页面；完成过盾后，再点击「完成验证」。").assertIsDisplayed()
        val refresh = compose.onNodeWithTag("verification-refresh").fetchSemanticsNode().boundsInRoot
        val complete = compose.onNodeWithTag("verification-complete").fetchSemanticsNode().boundsInRoot
        assertTrue(refresh.right <= complete.left)
        compose.runOnIdle {
            assertEquals(listOf("https://linux.do/challenge"), loads)
            assertEquals("fixture-agent", web.settings.userAgentString)
            assertEquals(WebSettings.LOAD_NO_CACHE, web.settings.cacheMode)
            web.webViewClient.onPageFinished(web, "https://linux.do/404")
        }
        repeat(3) { compose.onNodeWithTag("verification-refresh").performClick() }
        compose.runOnIdle { assertEquals(List(4) { "https://linux.do/challenge" }, loads) }
    }

    @Test fun pageFinishDoesNotCompleteAndTheNativeCheckBlocksDuplicateCompletion() {
        var checks = 0
        var completions = 0
        val response = CompletableDeferred<Unit>()
        compose.setContent { KudoTheme(DisplaySettings()) {
            VerificationScreen("fixture-agent", {}, { checks++; response.await() }, { completions++ }, ::factory)
        } }
        compose.runOnIdle {
            web.webViewClient.onPageFinished(web, "https://linux.do/")
            assertEquals(0, completions)
        }
        compose.onNodeWithTag("verification-complete").performClick()
        compose.onNodeWithTag("verification-complete").assertIsNotEnabled()
        compose.onNodeWithTag("verification-refresh").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(0, completions); response.complete(Unit) }
        compose.waitUntil { completions == 1 }
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun failedNativeCheckKeepsTheVerificationPageAndAllowsRefresh() {
        var completions = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            VerificationScreen("fixture-agent", {}, { throw ApiFailure(FailureKind.VERIFICATION, "仍需网页验证") },
                { completions++ }, ::factory)
        } }
        compose.onNodeWithTag("verification-complete").performClick()
        compose.onNodeWithText("仍需网页验证").assertIsDisplayed()
        compose.onNodeWithTag("verification-complete").assertIsEnabled()
        compose.onNodeWithTag("verification-refresh").performClick()
        compose.runOnIdle { assertEquals(0, completions); assertEquals(2, loads.size) }
    }

    @Test fun verificationErrorUsesChallengeInsteadOfLoginAndSuccessfulGuestCheckRetriesOnce() {
        var logins = 0
        var retries = 0
        var nativeChecks = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/latest.json", chain.request().url.encodedPath)
            nativeChecks++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"topic_list":{"topics":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }, http = http)
        compose.setContent { KudoTheme(DisplaySettings()) {
            VerificationHost(client, "fixture-agent", ::factory) {
                ErrorState(ApiFailure(FailureKind.VERIFICATION, CloudflareVerificationMessage), { retries++ }, { logins++ })
            }
        } }
        compose.onNodeWithText("完成网页验证").performClick()
        compose.onNodeWithTag("verification-page").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, logins); assertEquals(0, retries) }
        compose.onNodeWithTag("verification-complete").performClick()
        compose.waitUntil { retries == 1 }
        compose.onNodeWithTag("verification-page").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, nativeChecks); assertEquals(0, logins); assertEquals(1, retries) }
    }

    @Test fun verificationHostForwardsMaterialPreferenceOnEveryOpen() {
        var advanced by mutableStateOf(false)
        val client = DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        })
        compose.setContent { KudoTheme(DisplaySettings()) {
            VerificationHost(client, "fixture-agent", ::factory, advancedMaterial = advanced) {
                ErrorState(ApiFailure(FailureKind.VERIFICATION, CloudflareVerificationMessage), {}, {})
            }
        } }
        listOf(false, true, false).forEach { enabled ->
            compose.runOnIdle { advanced = enabled }
            compose.onNodeWithText("完成网页验证").performClick()
            compose.onNodeWithTag("verification-page").assertIsDisplayed()
            if (enabled) compose.onNodeWithTag("material-page-content").assertExists()
            else compose.onNodeWithTag("material-page-content").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("verification-page").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(List(3) { "https://linux.do/challenge" }, loads) }
    }

    @Test fun manualSettingsVerificationReturnsWithoutChangingPreferencesOrOpeningLogin() {
        val checks = AtomicInteger()
        var updates = 0
        var otherActions = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("GET", chain.request().method)
            assertEquals("/latest.json", chain.request().url.encodedPath)
            checks.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"topic_list":{"topics":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }, http = http)
        compose.setContent { KudoTheme(DisplaySettings()) {
            VerificationHost(client, "fixture-agent", ::factory) {
                SettingsScreen(DisplaySettings(), { updates++ }, null, { otherActions++ }, { otherActions++ },
                    { otherActions++ }, networkSummary = "已开启", manageNetwork = { otherActions++ })
            }
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("手动过盾"))
        compose.onNodeWithText("手动过盾").performClick()
        compose.onNodeWithTag("verification-page").assertIsDisplayed()
        compose.onNodeWithText("完成验证").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("手动过盾").assertIsDisplayed().performClick()
        compose.onNodeWithTag("verification-complete").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("verification-page").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("手动过盾").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, checks.get())
            assertEquals(List(2) { "https://linux.do/challenge" }, loads)
            assertEquals(0, updates)
            assertEquals(0, otherActions)
        }
    }

    @Test fun connectChallengeKeepsItsOwnWebsiteActionInsteadOfOpeningTheMainSiteChallenge() {
        var websiteOpens = 0
        var mainSiteVerifications = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("connect.linux.do", chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .header("cf-mitigated", "challenge").body("<html>Just a moment...</html>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        val repository = ConnectRepository(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }, "fixture-agent", http = http)
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalWebVerification provides { _ -> mainSiteVerifications++ }) {
                TrustScreen(Member(1, "fixture", "Fixture", ""), repository, {}, { websiteOpens++ })
            }
        } }
        compose.waitUntil { compose.onAllNodesWithText("完成网页验证").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("完成网页验证").performClick()
        compose.runOnIdle { assertEquals(1, websiteOpens); assertEquals(0, mainSiteVerifications) }
    }
}
