package com.kudo.android

import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.DisplaySettings
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.web.WebScreen
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in website compatibility check; does not publish, rate or change account data. */
class MiniAppLiveProbeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var web: WebView

    @Test fun officialSquareAndAppsLoadWithNativeAbout() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("miniAppLiveProbe") == "true")
        compose.setContent { KudoTheme(DisplaySettings()) {
            WebScreen("https://linux.do/apps", {}, {}, { context: Context -> WebView(context).also { web = it } })
        } }
        try {
            compose.waitUntil(45000) { js("document.querySelectorAll('a[href^=\"/apps/\"]').length").toIntOrNull()?.let { it > 0 } == true }
        } catch (error: Throwable) {
            capture("mini-app-live-square-failure.png")
            val diagnostics = js("JSON.stringify({url:location.href,title:document.title,text:document.body?.innerText.slice(0,1000)})")
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "mini-app-live-failure.json")
                .writeText(diagnostics, Charsets.UTF_8)
            throw error
        }
        compose.onNodeWithText("应用广场").assertIsDisplayed()
        capture("mini-app-live-square.png")
        for ((id, name) in listOf(
            "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281" to "2048",
            "01a19674-905b-7246-99ff-2f5e270a3f8f" to "能力测试",
        )) {
            compose.runOnUiThread { web.loadUrl("https://linux.do/apps/$id") }
            compose.waitUntil(45000) { js("Array.from(document.querySelectorAll('[data-role=stage]')).some(x=>x.textContent.includes('已就绪'))") == "true" }
            compose.onNodeWithTag("mini-app-capsule").assertIsDisplayed()
            capture("mini-app-live-$name.png")
            compose.onNodeWithContentDescription("小程序更多").performClick()
            compose.onNodeWithText("关于", substring = false).performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithTag("mini-app-about").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(name, substring = false).assertIsDisplayed()
            compose.onNodeWithText("Neo", substring = false).assertIsDisplayed()
            capture("mini-app-live-about-$name.png")
            compose.onNodeWithText("关闭", substring = false).performClick()
        }
    }

    private fun js(script: String): String {
        if (!::web.isInitialized) return "null"
        val latch = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
}
