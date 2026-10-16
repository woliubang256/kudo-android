package com.kudo.android

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.account.AccountViewModel
import com.kudo.android.ui.account.LoginScreen
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoginWebViewUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: FixtureWebView
    private lateinit var vm: AccountViewModel
    private var loads = 0
    private var sessionResets = 0

    @Before fun account() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        vm = AccountViewModel(CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }, http = http)), { sessionResets++ }, {})
    }

    @After fun release() { vm.viewModelScope.cancel() }

    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread { view.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test fun captchaFrameKeepsItsHeightAndViewportTracksAvailableSpaceWithoutReload() {
        var available by mutableStateOf(600.dp)
        compose.setContent { KudoTheme(DisplaySettings()) {
            Box(Modifier.fillMaxSize()) { Box(Modifier.fillMaxWidth().height(available)) {
                LoginScreen(vm, {}, {}, { FixtureWebView(it).also { web -> view = web } })
            } }
        } }
        compose.waitUntil(10_000) { js("document.getElementById('captcha') !== null") == "true" }
        val original = JSONArray(js("[innerHeight, document.getElementById('vh').offsetHeight, document.getElementById('captcha').offsetHeight]"))
        assertTrue("100vh must reflect the native viewport: $original", original.getDouble(1) > 100)
        assertEquals("Website's 200vh cap must not hide captcha", 76.0, original.getDouble(2), 1.0)
        compose.runOnIdle { available = 400.dp }
        compose.waitUntil(5_000) { JSONArray(js("[innerHeight]")).getDouble(0) < original.getDouble(0) - 100 }
        val resized = JSONArray(js("[innerHeight, document.getElementById('vh').offsetHeight, document.getElementById('captcha').offsetHeight]"))
        assertEquals(resized.getDouble(0), resized.getDouble(1), 2.0)
        assertEquals(76.0, resized.getDouble(2), 1.0)
        compose.runOnIdle { assertEquals("Resizing must preserve the form", 1, loads) }
    }

    @Test fun completeLoginButtonRunsSessionCheckOnlyOncePerTap() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            LoginScreen(vm, {}, {}, { FixtureWebView(it).also { web -> view = web } })
        } }
        compose.waitUntil(10_000) { sessionResets == 1 }
        compose.onNodeWithText("完成登录").performClick()
        compose.waitUntil(10_000) { sessionResets == 2 }
        Thread.sleep(100)
        assertEquals("A single tap must not reset the session twice", 2, sessionResets)
    }

    private inner class FixtureWebView(context: Context) : WebView(context) {
        override fun loadUrl(url: String) {
            loads++
            loadDataWithBaseURL("https://login-fixture.test/", """<!doctype html><html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>iframe {border:0;max-height:min(1000px,200vh)} #vh {height:100vh;position:absolute;visibility:hidden}</style>
                </head><body><div id="vh"></div><iframe id="captcha" style="width:302px;height:76px" srcdoc="captcha fixture"></iframe>
                <input aria-label="fixture input"></body></html>""", "text/html", "UTF-8", null)
        }
    }
}
