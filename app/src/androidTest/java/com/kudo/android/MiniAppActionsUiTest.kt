package com.kudo.android

import android.content.Context
import android.graphics.Bitmap
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.lifecycle.Lifecycle
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.web.WebScreen
import com.kudo.android.ui.web.FloatingMaterialKey
import com.kudo.android.ui.web.FloatingSampleKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MiniAppActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"
    private lateinit var web: FixtureView
    private var account by mutableStateOf<Int?>(101)
    private var display by mutableStateOf(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f))
    private var loggedIn = 0

    private fun show() {
        compose.setContent { KudoTheme(display) {
            key(account) { WebScreen("https://linux.do/apps/$id", {}, {}, { FixtureView(it).also { web = it } },
                accountId = account, login = { loggedIn++ }) }
        } }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("web-loading").fetchSemanticsNodes().isEmpty() && ::web.isInitialized &&
            js("window.authorized === true") == "true" }
    }
    private fun js(code: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread { web.evaluateJavascript(code) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }
    private fun open(label: String, ready: String) {
        compose.onNodeWithContentDescription("小程序更多").performClick()
        compose.onNodeWithText(label, substring = false).performClick()
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithText(ready, substring = false).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            println(compose.onRoot(useUnmergedTree = true).printToString())
            println(js("JSON.stringify({action:typeof window.__kudoMiniAppAction,reads:window.reads,writes:window.writes})"))
            capture("mini-app-actions-failure.png")
            throw failure
        }
    }
    private fun closePanel() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { compose.onAllNodesWithTag("liquid-action-sheet").fetchSemanticsNodes().isEmpty() }
    }
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }

    @Test fun ratingCanBeChangedAndUpdatesAboutWithoutReloadingTheGame() {
        show()
        val original = web
        open("评分", "4.25 / 5 · 8 人评分")
        compose.onNodeWithContentDescription("评为 3 分，满分 5 分").assertIsSelected()
        compose.onNodeWithContentDescription("评为 5 分，满分 5 分").performClick()
        compose.waitUntil { compose.onAllNodesWithText("评分已更新").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("评为 5 分，满分 5 分").assertIsSelected()
        compose.onNodeWithContentDescription("评为 2 分，满分 5 分").performClick()
        compose.waitUntil { compose.onAllNodes(hasContentDescription("评为 2 分，满分 5 分") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("2", js("writes.filter(x=>x.path.endsWith('/rating')).length"))
        closePanel()
        open("关于", "8 人评分")
        compose.onNodeWithContentDescription("评分 2.0 分，满分 5 分").assertIsDisplayed()
        compose.runOnIdle { assertSame(original, web); assertEquals(1, web.loads) }
    }

    @Test fun tippingRequiresExplicitConfirmationAndShowsTheServerCooldown() {
        show()
        open("打赏", "余额：10 积分")
        compose.onNodeWithText("确认打赏", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("3", substring = false).performClick()
        assertEquals("0", js("writes.length"))
        compose.onNodeWithText("确认打赏 3 积分").performClick()
        compose.waitUntil { compose.onAllNodesWithText("已打赏 3 积分").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("余额：7 积分").assertIsDisplayed()
        compose.onNodeWithText("确认打赏", substring = false).assertIsNotEnabled()
        assertEquals("1", js("writes.length"))
        val data = JSONObject(JSONArray(js("writes")).getJSONObject(0).getString("body"))
        assertEquals(3, data.getInt("amount")); assertEquals(36, data.getString("request_id").length)
        capture("mini-app-tip-dark.png")
    }

    @Test fun reportingUsesTheRunningReleaseAndOnlyApplicableFlagReasons() {
        show()
        // Authorization renewal returns a newer release, while the game remains on 328.
        js("fetch('/mini-app-api/production/$id/launch').then(()=>window.renewed=true)")
        compose.waitUntil { js("window.renewed===true") == "true" }
        open("举报", "其他内容")
        compose.onNodeWithText("仅帖子原因").assertDoesNotExist()
        compose.onNodeWithText("已禁用原因").assertDoesNotExist()
        compose.onNodeWithText("联系作者").assertDoesNotExist()
        compose.onNodeWithText("其他内容").performClick()
        compose.onNodeWithTag("mini-app-report-message").performClick().performTextInput("太短")
        compose.onNodeWithText("提交举报").assertIsNotEnabled()
        compose.onNodeWithTag("mini-app-report-message").performTextReplacement("这是完整的举报说明")
        compose.onNodeWithText("提交举报").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("举报已提交").fetchSemanticsNodes().isNotEmpty() }
        val data = JSONObject(JSONArray(js("writes")).getJSONObject(0).getString("body"))
        assertEquals(328, data.getInt("release_id")); assertEquals(8, data.getInt("flag_type_id"))
        assertEquals("这是完整的举报说明", data.getString("message"))
        compose.onNodeWithText("提交举报").assertIsNotEnabled()
        capture("mini-app-report-dark.png")
    }

    @Test fun guestsCanSeeTheActionsButMustSignInBeforeAnyWrite() {
        account = null
        show()
        open("评分", "请先登录后再操作")
        compose.onNodeWithText("登录", substring = false).performClick()
        compose.runOnIdle { assertEquals(1, loggedIn) }
        assertEquals("0", js("writes.length"))
    }

    @Test fun actionsFollowMaterialsAndRemainReadableAcrossThemes() {
        show()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { display = display.copy(theme = theme, glass = glass) }
            for ((label, ready) in listOf("评分" to "4.25 / 5 · 8 人评分", "打赏" to "余额：10 积分", "举报" to "其他内容")) {
                open(label, ready)
                val panel = compose.onNodeWithTag("mini-app-material-panel", useUnmergedTree = true)
                panel.assert(SemanticsMatcher.expectValue(FloatingMaterialKey, glass))
                if (glass != GlassMode.TRANSLUCENT) compose.waitUntil(6000) { panel.fetchSemanticsNode().config[FloatingSampleKey] }
                else panel.assert(SemanticsMatcher.expectValue(FloatingSampleKey, false))
                if (label == "评分") (1..5).forEach { compose.onNodeWithContentDescription("评为 $it 分，满分 5 分").assertIsDisplayed() }
                compose.onNodeWithTag("material-page-content").assertDoesNotExist()
                if (glass == GlassMode.LIQUID) capture("mini-app-${label}-${theme.name.lowercase()}-material.png")
                closePanel()
            }
        }
        compose.onNodeWithContentDescription("小程序更多").performClick()
        listOf("评分", "打赏", "举报").forEach { compose.onNodeWithText(it, substring = false).assertIsDisplayed() }
        capture("mini-app-community-menu.png")
    }

    @Test fun serverDenialAndUnconfirmedReportNeverProduceAnotherWrite() {
        show()
        js("window.denyRating=true")
        open("评分", "当前账号暂时不能为此应用评分")
        compose.onNodeWithContentDescription("评为 5 分，满分 5 分").assertDoesNotExist()
        closePanel()
        js("window.loseReport=true")
        open("举报", "其他内容")
        compose.onNodeWithText("垃圾信息").performClick()
        compose.onNodeWithText("提交举报").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("上次举报结果尚未确认，请到网站核对后再操作。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("提交举报").assertIsNotEnabled()
        closePanel()
        open("举报", "上次举报结果尚未确认，请到网站核对后再操作")
        assertEquals("1", js("writes.length"))
    }

    @Test fun busyPanelRejectsSystemBackScrimAndDragUntilTheWebsiteFinishes() {
        show()
        open("评分", "4.25 / 5 · 8 人评分")
        js("window.holdRating=true")
        compose.onNodeWithContentDescription("评为 5 分，满分 5 分").performClick()
        compose.waitUntil { js("typeof window.finishRating==='function'") == "true" }
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.onNodeWithTag("modal-scrim").performClick()
        compose.onNodeWithTag("sheet-drag-handle").performTouchInput { swipeDown() }
        compose.onNodeWithTag("mini-app-material-panel").assertIsDisplayed()
        assertEquals("1", js("writes.length"))
        js("window.finishRating();window.finishRating=undefined")
        compose.waitUntil { compose.onAllNodesWithText("评分已更新").fetchSemanticsNodes().isNotEmpty() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil { compose.onAllNodesWithTag("liquid-action-sheet").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun reportInputRetainsFocusAndTextWhenMaterialChanges() {
        show()
        open("举报", "其他内容")
        compose.onNodeWithText("其他内容").performClick()
        val input = compose.onNodeWithTag("mini-app-report-message")
        input.performClick().performTextInput("切换材质后保留的举报内容")
        for (glass in GlassMode.entries) {
            compose.runOnIdle { display = display.copy(glass = glass) }
            input.assertIsFocused().assertTextContains("切换材质后保留的举报内容")
            compose.onNodeWithTag("mini-app-material-panel", useUnmergedTree = true)
                .assert(SemanticsMatcher.expectValue(FloatingMaterialKey, glass))
        }
        capture("mini-app-report-material-keyboard.png")
        assertEquals("0", js("writes.length"))
    }

    @Test fun alreadyStartedRatingFinishesWhileBackgroundedAndDoesNotLeaveTheMenuBusy() {
        show()
        open("评分", "4.25 / 5 · 8 人评分")
        js("window.holdRating=true")
        compose.onNodeWithContentDescription("评为 5 分，满分 5 分").performClick()
        compose.waitUntil { js("typeof window.finishRating==='function'") == "true" }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        js("window.finishRating();window.finishRating=undefined")
        // Flush the page/native message queues before resuming; the HTTP operation
        // already started in foreground and must be allowed to finish in background.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        js("true")
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil { compose.onAllNodesWithTag("liquid-action-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("小程序更多").performClick()
        compose.onNodeWithText("评分", substring = false).assertIsEnabled()
        compose.onNodeWithText("关于", substring = false).performClick()
        compose.waitUntil { compose.onAllNodesWithContentDescription("评分 5.0 分，满分 5 分").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("1", js("writes.length"))
    }

    private inner class FixtureView(context: Context) : WebView(context) {
        private val appId = this@MiniAppActionsUiTest.id
        var loads = 0
        init {
            WebViewCompat.addDocumentStartJavaScript(this, """
                window.writes=[];window.reads=[];window.score=3;window.balance=10;window.launches=0;window.loseReport=false;
                const reply=data=>Promise.resolve(new Response(JSON.stringify(data),{status:200,headers:{'Content-Type':'application/json'}}));
                window.fetch=(input,options={})=>{
                  const path=new URL(typeof input==='string'?input:input.url,location.href).pathname;
                  reads.push(path);
                  if(options.method&&options.method!=='GET')writes.push({path,body:options.body,csrf:options.headers['X-CSRF-Token']});
                  if(path.endsWith('/launch'))return reply({release:{id:328+launches++,app_id:'$appId',channel:'production'}});
                  if(path==='/session/csrf')return reply({csrf:'fixture-csrf'});
                  if(path.endsWith('/info'))return reply({app:{app_id:'$appId',release_id:999,name:'测试小程序',version:'1.0.0',
                    can_rate:!window.denyRating,can_tip:true,can_report:true,my_rating:score,rating_average:window.average||4.25,rating_count:8}});
                  if(path.endsWith('/rating')){if(options.headers['X-CSRF-Token']!=='fixture-csrf')throw Error('csrf');
                    score=JSON.parse(options.body).score;window.average=score;
                    const data={my_rating:score,rating_average:score,rating_count:8};
                    if(window.holdRating)return new Promise(resolve=>window.finishRating=()=>resolve(new Response(JSON.stringify(data),{status:200})));
                    return reply(data)}
                  if(path.endsWith('/tip')){if(options.method==='POST'){balance-=JSON.parse(options.body).amount;window.next='2099-01-01T00:00:00Z'}
                    return reply({amounts:[1,3,5,10],balance,next_tip_at:window.next||null,...options.method==='POST'?{amount:JSON.parse(options.body).amount}:{}})}
                  if(path==='/site.json')return reply({post_action_types:[
                    {id:3,name_key:'spam',name:'垃圾信息',enabled:true,applies_to:['DiscourseMiniApp::App'],position:1},
                    {id:8,name_key:'notify_moderators',name:'其他内容',require_message:true,enabled:true,applies_to:['DiscourseMiniApp::App'],position:2},
                    {id:6,name_key:'notify_user',name:'联系作者',enabled:true,applies_to:['DiscourseMiniApp::App']},
                    {id:10,name_key:'other',name:'仅帖子原因',enabled:true,applies_to:['Post']},
                    {id:11,name_key:'disabled',name:'已禁用原因',enabled:false,applies_to:['DiscourseMiniApp::App']}]});
                  if(path.endsWith('/report')){if(window.loseReport)return Promise.reject(new TypeError('fixture lost response'));return reply({success:true})}
                  return Promise.reject(new Error('Unexpected fixture URL '+path));
                };
            """.trimIndent() + "\n;delete window.__kudoMiniAppAction;\n" +
                context.assets.open("mini-app-actions.js").bufferedReader(Charsets.UTF_8).use { it.readText() }, setOf("https://linux.do"))
        }
        override fun setWebViewClient(client: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width">
                        <script id="data-preloaded" type="application/json">{"siteSettings":{"min_personal_message_post_length":5}}</script>
                        </head><body><main><section class="mini-app-page"><div class="mini-app-loader">小程序内容</div></section></main>
                        <script>fetch('/mini-app-api/production/$appId/launch').then(()=>window.authorized=true)</script></body></html>"""
                    return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                }
                override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) { loads++; client.onPageStarted(view, url, icon) }
                override fun onPageFinished(view: WebView, url: String) = client.onPageFinished(view, url)
                override fun onPageCommitVisible(view: WebView, url: String) = client.onPageCommitVisible(view, url)
                override fun doUpdateVisitedHistory(view: WebView, url: String, reload: Boolean) = client.doUpdateVisitedHistory(view, url, reload)
            })
        }
    }
}
