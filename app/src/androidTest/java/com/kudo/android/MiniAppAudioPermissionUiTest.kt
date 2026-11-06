package com.kudo.android

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.web.WebScreen
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MiniAppAudioPermissionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"
    private val other = "01a1966e-935a-79e0-8c76-9b0f7552c578"
    private lateinit var web: FixtureView
    private var account by mutableStateOf(101)
    private var display by mutableStateOf(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f))

    private fun show() {
        compose.setContent { KudoTheme(display) {
            key(account) { WebScreen("https://linux.do/apps/$id", {}, {}, { FixtureView(it).also { web = it } }, accountId = account) }
        } }
        compose.waitUntil(15000) { ::web.isInitialized && web.finishedUrl.isNotBlank() &&
            compose.onAllNodesWithTag("web-loading").fetchSemanticsNodes().isEmpty() && js("typeof requestAudio === 'function'") == "true" }
    }

    private fun js(code: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        compose.runOnUiThread { web.evaluateJavascript(code) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun request(translated: Boolean = false, unknown: Boolean = false) {
        js("requestAudio($translated, $unknown);true")
        if (!unknown) compose.waitUntil(5000) { compose.onAllNodesWithText("允许播放声音？").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitResult(value: String) {
        try { compose.waitUntil(8000) { js("window.audioResult") == "\"$value\"" } }
        catch (failure: Throwable) {
            throw AssertionError("Website audio result: ${js("JSON.stringify({result:window.audioResult,trusted:window.trustedChoice,active:window.activeChoice})")}", failure)
        }
    }

    @Test fun bothNativeChoicesUseTheOriginalWebsiteCallbacksWithoutRecreatingTheGame() {
        show()
        val original = web
        for (muted in listOf(true, false)) {
            request()
            assertEquals("\"pending\"", js("window.audioResult"))
            assertEquals("false", js("!!document.querySelector('dialog[open]')"))
            compose.onNodeWithText(if (muted) "静音进入" else "开启声音").performTouchInput { click() }
            awaitResult(if (muted) "muted" else "enabled")
            assertEquals("true", js("window.trustedChoice && window.activeChoice"))
            assertEquals(muted.toString(), js("window.audioPool.every(media=>media.muted)"))
            compose.runOnIdle { assertSame(original, web); assertTrue(web.settings.mediaPlaybackRequiresUserGesture) }
        }
    }

    @Test fun nativeAudioPermissionRemainsOrdinaryAcrossThemesAndMaterials() {
        show()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { display = display.copy(theme = theme, glass = glass) }
            request()
            compose.onNodeWithText("静音进入").assertIsDisplayed()
            compose.onNodeWithText("开启声音").assertIsDisplayed()
            compose.onNodeWithTag("material-page-content").assertDoesNotExist()
            if (glass == GlassMode.LIQUID) {
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                    "mini-app-audio-${theme.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("静音进入").performClick()
            awaitResult("muted")
        }
    }

    @Test fun translatedAudioUsesNativeButUnknownPermissionKeepsItsOriginalWebDialog() {
        show()
        request(translated = true)
        compose.onNodeWithText("开启声音").performClick()
        awaitResult("enabled")
        request(unknown = true)
        assertEquals("true", js("!!document.querySelector('dialog[open]')"))
        compose.onNodeWithText("允许播放声音？").assertDoesNotExist()
        assertEquals("\"pending\"", js("window.audioResult"))
        js("document.querySelector('dialog').dispatchEvent(new Event('cancel',{cancelable:true}))")
        awaitResult("cancelled")
    }

    @Test fun backBackgroundNavigationAndAccountChangeCancelUnansweredRequests() {
        show()
        request()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitResult("cancelled")
        request()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitResult("cancelled")
        compose.onNodeWithText("允许播放声音？").assertDoesNotExist()
        request()
        js("history.pushState({},'', '/apps/$other')")
        awaitResult("cancelled")
        request()
        val previous = web
        compose.runOnIdle { account = 202 }
        compose.waitUntil(10000) { web !== previous && js("typeof requestAudio === 'function'") == "true" }
        compose.onNodeWithText("允许播放声音？").assertDoesNotExist()
        assertEquals("\"undefined\"", js("typeof window.trustedChoice"))
    }

    @Test fun removedRequestsAndEmbeddedOrUntrustedPagesDoNotReceiveNativeChoices() {
        show()
        request()
        js("document.querySelector('dialog').remove()")
        compose.waitUntil(5000) { compose.onAllNodesWithText("允许播放声音？").fetchSemanticsNodes().isEmpty() }
        assertEquals("\"undefined\"", js("typeof window.trustedChoice"))
        request()
        compose.onNodeWithText("静音进入").performClick()
        awaitResult("muted")

        js("window.child=document.createElement('iframe');child.src='/apps/$id';document.body.append(child)")
        compose.waitUntil(5000) { js("typeof child.contentWindow.requestAudio === 'function'") == "true" }
        js("child.contentWindow.requestAudio()")
        assertEquals("true", js("!!child.contentDocument.querySelector('dialog[open]')"))
        compose.onNodeWithText("允许播放声音？").assertDoesNotExist()

        js("location.href='https://example.com/apps/$id'")
        compose.waitUntil(10000) { web.finishedUrl.startsWith("https://example.com/") }
        assertEquals("true", js("typeof requestAudio === 'function'"))
        js("requestAudio()")
        assertEquals("true", js("!!document.querySelector('dialog[open]')"))
        compose.onNodeWithText("允许播放声音？").assertDoesNotExist()
    }

    @Test fun failedInputHandoffRestoresTheOriginalWebDialog() {
        show()
        request()
        compose.runOnIdle { web.swallowInput = true }
        compose.onNodeWithText("开启声音").performClick()
        compose.waitUntil(7000) { compose.onAllNodesWithText("允许播放声音？").fetchSemanticsNodes().isEmpty() &&
            js("!!document.querySelector('dialog[open]') && document.querySelector('dialog').style.opacity !== '0'") == "true" }
        assertEquals("\"pending\"", js("window.audioResult"))
        compose.runOnIdle { web.swallowInput = false }
        val point = JSONArray(js("(()=>{const b=document.querySelector('dialog .--primary').getBoundingClientRect();return [b.x+b.width/2,b.y+b.height/2,innerWidth]})()"))
        val node = compose.onNodeWithTag("web-content")
        val scale = node.fetchSemanticsNode().boundsInRoot.width / point.getDouble(2).toFloat()
        node.performTouchInput { click(Offset(point.getDouble(0).toFloat() * scale, point.getDouble(1).toFloat() * scale)) }
        awaitResult("enabled")
    }

    private inner class FixtureView(context: Context) : WebView(context) {
        @Volatile var finishedUrl = ""
        var swallowInput = false
        override fun dispatchTouchEvent(event: MotionEvent) = if (swallowInput) true else super.dispatchTouchEvent(event)
        override fun setWebViewClient(client: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                        <style>dialog{background:white;color:black;padding:20px;width:75%}button{min-height:48px;min-width:90px}</style>
                        </head><body><main><section class="mini-app-page"><div class="mini-app-loader">游戏内容</div></section></main>
                        <script>
                        window.fetch=()=>Promise.resolve(new Response(JSON.stringify({app:{app_id:'${this@MiniAppAudioPermissionUiTest.id}',name:'测试小程序',version:'1.0.0'}}),{status:200}));
                        function silentSource(){const data=new Uint8Array(204),v=new DataView(data.buffer);for(const [offset,text] of [[0,'RIFF'],[8,'WAVE'],[12,'fmt '],[36,'data']])data.set(new TextEncoder().encode(text),offset);
                          v.setUint32(4,data.length-8,true);v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);v.setUint32(24,8000,true);v.setUint32(28,16000,true);v.setUint16(32,2,true);v.setUint16(34,16,true);v.setUint32(40,data.length-44,true);return URL.createObjectURL(new Blob([data],{type:'audio/wav'}))}
                        window.audioPool=[];
                        window.requestAudio=(translated=false,unknown=false)=>{
                          for(const media of audioPool){media.pause();media.removeAttribute('src');media.load()}audioPool=[];
                          window.audioResult='pending';window.trustedChoice=undefined;window.activeChoice=undefined;
                          let preload=document.getElementById('data-preloaded');if(!preload){preload=document.createElement('script');preload.id='data-preloaded';preload.type='application/json';document.head.append(preload)}
                          preload.textContent=JSON.stringify({['mini-app/production/'+location.pathname.split('/').pop()+'/']:{configuration:{channel:'production',translations:{native:{
                            action:{audio:{play:translated?'Play audio':'播放音频'}},audioSessionNotice:translated?'Audio can play during this run.':'此应用在本次运行中可播放声音。',enterMuted:translated?'Enter muted':'静音进入',enableAudio:translated?'Enable sound':'开启声音'
                          }}}}});
                          const d=document.createElement('dialog');d.className='mini-app-permission-dialog';
                          const heading=document.createElement('h2');heading.id='permission-'+crypto.randomUUID();heading.textContent='测试小程序 请求操作';d.setAttribute('aria-labelledby',heading.id);
                          const action=document.createElement('p');action.textContent=unknown?'未知权限':translated?'Play audio':'播放音频';
                          const preview=document.createElement('p');preview.className='mini-app-permission-dialog__preview';preview.textContent=translated?'Audio can play during this run.':'此应用在本次运行中可播放声音。';
                          const buttons=document.createElement('div'),cancel=document.createElement('button'),allow=document.createElement('button');cancel.type=allow.type='button';cancel.className='button';allow.className='button --primary';
                          cancel.textContent=translated?'Enter muted':'静音进入';allow.textContent=translated?'Enable sound':'开启声音';buttons.append(cancel,allow);d.append(heading,action,preview,buttons);
                          let settled=false;const choose=async(muted,event)=>{if(settled)return;settled=true;cancel.disabled=allow.disabled=true;window.trustedChoice=event.isTrusted;window.activeChoice=navigator.userActivation.isActive;
                            d.close();const source=silentSource();try{for(let i=0;i<4;i++){const media=document.createElement('audio');media.src=source;audioPool.push(media)}
                              await Promise.all(audioPool.map(media=>media.play()));for(const media of audioPool){media.pause();media.muted=muted}window.audioResult=muted?'muted':'enabled';
                            }catch(error){window.audioResult=error.name}finally{URL.revokeObjectURL(source);d.remove()}};
                          cancel.addEventListener('click',event=>choose(true,event));allow.addEventListener('click',event=>choose(false,event));
                          d.addEventListener('cancel',event=>{event.preventDefault();if(!settled){settled=true;d.close();d.remove();window.audioResult='cancelled'}});
                          document.body.append(d);d.showModal();
                        };
                        </script></body></html>"""
                    return WebResourceResponse("text/html", "UTF-8", html.byteInputStream())
                }
                override fun onPageStarted(view: WebView, url: String, icon: Bitmap?) = client.onPageStarted(view, url, icon)
                override fun onPageFinished(view: WebView, url: String) { finishedUrl = url; client.onPageFinished(view, url) }
                override fun onPageCommitVisible(view: WebView, url: String) = client.onPageCommitVisible(view, url)
                override fun doUpdateVisitedHistory(view: WebView, url: String, reload: Boolean) = client.doUpdateVisitedHistory(view, url, reload)
            })
        }
    }
}
