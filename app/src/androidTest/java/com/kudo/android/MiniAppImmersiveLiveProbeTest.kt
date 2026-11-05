package com.kudo.android

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.DisplaySettings
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.web.WebScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in public app probe; diagnostics exclude tokens, cookies and launch responses. */
class MiniAppImmersiveLiveProbeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var web: WebView
    private val id = "01a1a081-677f-760e-9af7-ad8822216771"

    @Test fun inspectOfficialEmbeddedRuntime() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("miniAppEmbedProbe") == "true")
        val records = JSONArray()
        compose.setContent { KudoTheme(DisplaySettings()) {
            var visible by remember { mutableStateOf(true) }
            if (visible) WebScreen("https://linux.do/apps/$id", { visible = false }, {}, { context -> WebView(context).also { web = it } })
            else Text("原生应用目录")
        } }
        try {
            for ((index, appId) in listOf(id, "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281").withIndex()) {
                val stages = JSONArray()
                val captured = mutableSetOf<String>()
                if (index > 0) compose.runOnUiThread { web.loadUrl("https://linux.do/apps/$appId?embed_mode=true") }
                compose.waitUntil(70000) {
                    if (js("location.pathname === '/apps/$appId'") != "true") return@waitUntil false
                    val snapshot = js("JSON.stringify({stage:document.querySelector('[data-role=stage]')?.textContent,progress:(()=>{const p=document.querySelector('.mini-app-loader progress');return p?.hasAttribute('value')?p.value/p.max:null})(),native:document.documentElement.hasAttribute('data-kudo-mini-app-native-progress')})")
                    runCatching { JSONObject(JSONArray("[$snapshot]").getString(0)) }.getOrNull()?.let { state ->
                        val stage = state.optString("stage")
                        if (stage.isNotEmpty() && captured.add(stage)) {
                            stages.put(state)
                            if (compose.onAllNodesWithTag("mini-app-launch").fetchSemanticsNodes().isNotEmpty()) capture("mini-app-live-launch-$index-${stages.length()}.png")
                        }
                    }
                    if (js("!!document.querySelector('.mini-app-permission-dialog[open]')") == "true") {
                        capture("mini-app-immersive-permission.png")
                        // The first action enters muted; do not grant unrelated business permissions.
                        tap(".mini-app-permission-dialog button")
                    }
                    js("!!document.querySelector('.mini-app-loader:not(.is-loading):not(.is-error) .mini-app-frame-host') || !!document.querySelector('.mini-app-loader.is-error') || location.href.startsWith('chrome-error:')") == "true"
                }
                js("window.__kudoPublisherProbe=null;fetch('/mini-app-api/public/$appId/info',{credentials:'same-origin'}).then(r=>r.json()).then(d=>{const a=d.app?.author||{};window.__kudoPublisherProbe={keys:Object.keys(a),name:a.name,avatar_template:a.avatar_template,avatar_url:a.avatar_url}})")
                compose.waitUntil(15000) { js("!!window.__kudoPublisherProbe") == "true" }
                val data = js("""JSON.stringify({
                  appId: '$appId', title: document.title, viewport: [innerWidth, innerHeight],
                  publisher: window.__kudoPublisherProbe,
                  adapted: document.documentElement.hasAttribute('data-kudo-mini-app'),
                  ready: !!document.querySelector('.mini-app-loader:not(.is-loading):not(.is-error) .mini-app-frame-host'),
                  stage: document.querySelector('[data-role=stage]')?.textContent,
                  text: document.body.innerText.slice(0,1000),
                  elements: [...document.querySelectorAll('[class]')].filter(e => /mini-app|app-detail|d-header|main-outlet/.test(e.className)).slice(0,50)
                    .map(e => ({tag:e.tagName,cls:e.className,id:e.id,display:getComputedStyle(e).display,rect:[e.getBoundingClientRect().x,e.getBoundingClientRect().y,e.clientWidth,e.clientHeight]})),
                  frames: [...document.querySelectorAll('iframe')].map(f => ({path:new URL(f.src,location.href).pathname,cls:f.className,rect:[f.getBoundingClientRect().x,f.getBoundingClientRect().y,f.clientWidth,f.clientHeight],
                    host: (()=>{try{const d=f.contentDocument;return d?{style:!!d.getElementById('kudo-mini-app-presentation'),sheet:!!d.getElementById('kudo-mini-app-presentation')?.sheet,viewport:[f.contentWindow.innerWidth,f.contentWindow.innerHeight],elements:[d.documentElement,d.body,d.querySelector('iframe')].filter(Boolean).map(e=>({tag:e.tagName,style:e.getAttribute('style'),height:e.getAttribute('height'),size:[e.clientWidth,e.clientHeight],css:{height:getComputedStyle(e).height,minHeight:getComputedStyle(e).minHeight,display:getComputedStyle(e).display}}))}:null}catch(_){return null}})(),
                    runtime: (()=>{try{const r=f.contentDocument?.querySelector('iframe');return r?{size:[r.clientWidth,r.clientHeight],sandbox:r.getAttribute('sandbox'),embedded:new URL(r.src).searchParams.get('embed_mode')}:null}catch(_){return null}})()}))
                })""".trimIndent())
                val record = JSONObject(JSONArray("[$data]").getString(0))
                record.put("launchSnapshots", stages)
                records.put(record)
                capture("mini-app-immersive-$index.png")
                assertTrue(record.toString(), record.getBoolean("ready"))
                assertTrue(record.getBoolean("adapted"))
                assertEquals("true", js("!document.querySelector('.d-header')||getComputedStyle(document.querySelector('.d-header')).display==='none'"))
                assertEquals("true", js("(()=>{const f=document.querySelector('.mini-app-frame-host'),r=f.contentDocument.querySelector('iframe');return Math.abs(f.clientHeight-innerHeight)<2&&Math.abs(r.clientHeight-innerHeight)<2&&Math.abs(r.clientWidth-innerWidth)<2})()"))
                compose.onNodeWithContentDescription("返回").assertDoesNotExist()
                compose.onNodeWithTag("mini-app-title-bar").assertIsDisplayed()
                val game = compose.onNodeWithTag("web-content").fetchSemanticsNode().boundsInRoot
                val capsule = compose.onNodeWithTag("mini-app-capsule").fetchSemanticsNode().boundsInRoot
                assertTrue("原生胶囊不能遮挡线上游戏", capsule.bottom <= game.top)
                compose.onNodeWithContentDescription("小程序更多").performClick()
                compose.onNodeWithText("关于", substring = false).performClick()
                compose.waitUntil(20000) { compose.onAllNodesWithTag("mini-app-about").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(if (index == 0) "东北雨姐galgame" else "2048", substring = false).assertIsDisplayed()
                assertTrue(compose.onAllNodes(hasContentDescription("的头像", substring = true)).fetchSemanticsNodes().isNotEmpty())
                capture("mini-app-immersive-about-$index.png")
                compose.onNodeWithText("关闭", substring = false).performClick()
            }
            compose.onNodeWithContentDescription("关闭网页").performClick()
            compose.onNodeWithText("原生应用目录").assertIsDisplayed()
        } finally {
            capture("mini-app-immersive-last.png")
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "mini-app-immersive-evidence.json")
                .writeText(records.toString(2), Charsets.UTF_8)
        }
    }

    private fun tap(selector: String) {
        val position = JSONArray(js("(()=>{const r=document.querySelector('$selector').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})()"))
        val node = compose.onNodeWithTag("web-content")
        val scale = node.fetchSemanticsNode().boundsInRoot.width / position.getDouble(2).toFloat()
        node.performTouchInput { click(Offset(position.getDouble(0).toFloat()*scale, position.getDouble(1).toFloat()*scale)) }
    }

    private fun js(script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        compose.runOnUiThread { web.evaluateJavascript(script) { result = it; done.countDown() } }
        // Navigation can replace the document before its evaluation callback returns.
        return if (done.await(5, TimeUnit.SECONDS)) result else "null"
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
}
