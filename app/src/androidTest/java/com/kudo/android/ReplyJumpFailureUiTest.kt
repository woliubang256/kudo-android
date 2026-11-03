package com.kudo.android

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.components.showKudoToast
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.theme.LogoYellow
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReplyJumpFailureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clickingAnUnavailableRecipientShowsTheFailureWithoutOpeningTheFloorEditor() {
        val posts = listOf(11, 12).joinToString(",") {
            """{"id":$it,"post_number":$it,"user_id":2,"username":"author","name":"回复作者","cooked":"<p>正文$it</p>","reply_to_post_number":10,"reply_to_user":{"id":10,"username":"pub4hs"}}"""
        }
        val topic = """{"id":5,"title":"回复跳转","highest_post_number":12,"post_stream":{"stream":[11,12],"posts":[$posts]}}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET")
            val body = when (request.url.encodedPath) {
                "/t/5.json", "/t/5/1.json", "/t/5/10.json" -> topic
                "/t/5/posts.json" -> """{"post_stream":{"posts":[]}}"""
                else -> error("Unexpected offline endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val cookies = object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) {}
        }
        val vm = TopicViewModel(CommunityRepository(DiscourseClient(cookies, "https://reply.test/".toHttpUrl(), http = http)), 5)
        try {
            compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
                val state by vm.state.collectAsState()
                val context = LocalContext.current
                LaunchedEffect(vm) { vm.events.collect { showKudoToast(context, it) } }
                TopicScreen(state, GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    readingEnabled = false, jumpTo = vm::jumpTo)
            } }
            compose.waitUntil(5_000) { !vm.state.value.loading && !vm.state.value.contextLoading }
            compose.onNodeWithTag("post-reply-recipient-11").performClick()
            compose.waitUntil(5_000) { vm.state.value.jumpError != null }
            compose.onNodeWithText("正文11").assertIsDisplayed()
            compose.onNodeWithTag("floor-input").assertDoesNotExist()
            assertEquals("此楼层不存在或暂不可访问", vm.state.value.jumpError)
            captureVisibleToast()
        } finally {
            vm.viewModelScope.cancel()
            http.dispatcher.executorService.shutdown()
        }
    }

    private fun captureVisibleToast() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 2_000
        var bitmap: Bitmap? = null
        var visible = false
        try {
            while (!visible && SystemClock.uptimeMillis() < deadline) {
                bitmap?.recycle()
                val shot = instrumentation.uiAutomation.takeScreenshot().also { bitmap = it }
                visible = (0 until shot.height / 3 step 4).any { y ->
                    (shot.width / 4 until shot.width * 3 / 4 step 4).count { x ->
                        shot.getPixel(x, y) == LogoYellow.toArgb()
                    } > shot.width / 20
                }
                if (!visible) SystemClock.sleep(50)
            }
            assertTrue("从回复对象跳转失败后必须实际显示短提示", visible)
            File(instrumentation.targetContext.getExternalFilesDir(null), "reply-jump-failure-toast.png").outputStream().use {
                bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap?.recycle() }
    }
}
