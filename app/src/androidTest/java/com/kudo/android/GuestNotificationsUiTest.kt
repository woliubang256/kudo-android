package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.AccountViewModel
import com.kudo.android.ui.account.NotificationsScreen
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GuestNotificationsUiTest {
    @get:Rule val compose = createComposeRule()
    private val signedIn = AtomicBoolean(false)
    private val writes = AtomicInteger(0)
    private lateinit var vm: AccountViewModel
    private lateinit var http: OkHttpClient

    @Before fun accountFixture() {
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method != "GET") writes.incrementAndGet()
            val body = when (request.url.encodedPath) {
                "/session/current.json" -> if (signedIn.get()) """{"current_user":{"id":42,"username":"fixture"}}""" else "{}"
                "/notifications" -> """{"notifications":[{"id":1,"topic_id":7,"post_number":2,"notification_type":2,"read":false,"data":{"title":"账号通知夹具","display_username":"fixture"}}]}"""
                else -> error("Unexpected fixture request: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        vm = AccountViewModel(CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }, baseUrl = "https://notifications.test/".toHttpUrl(), http = http)), {}, {})
    }

    @After fun release() { vm.viewModelScope.cancel(); http.dispatcher.executorService.shutdown() }

    @Test fun expiredSessionHidesOldNoticesAndAccountActionsAndOffersLogin() {
        signedIn.set(true)
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) { NotificationsScreen(vm, {}, { _, _ -> }) } }
        compose.waitUntil(5000) { !vm.checkingSession }
        compose.runOnIdle { vm.viewModelScope.launch { vm.checkSession() } }
        compose.waitUntil(5000) { vm.user != null && !vm.checkingSession }
        compose.runOnIdle { vm.loadNotices() }
        compose.waitUntil(5000) { !vm.noticesLoading && vm.notices.isNotEmpty() }
        compose.onNodeWithText("账号通知夹具").assertIsDisplayed()
        compose.onNodeWithContentDescription("刷新通知").assertIsDisplayed()
        compose.onNodeWithContentDescription("全部标为已读").assertIsDisplayed()
        compose.onNodeWithText("全部").assertIsDisplayed()
        compose.onNodeWithText("所有通知").assertDoesNotExist()
        compose.onNodeWithText("消息", substring = false).assertDoesNotExist()
        signedIn.set(false)
        compose.runOnIdle { vm.viewModelScope.launch { vm.checkSession() } }
        compose.waitUntil(5000) { vm.user == null && !vm.checkingSession }
        // Session transitions must also clear cached account data.
        compose.runOnIdle { assertEquals(0, vm.notices.size) }
        compose.onNodeWithText("账号通知夹具").assertDoesNotExist()
        compose.onNodeWithContentDescription("刷新通知").assertDoesNotExist()
        compose.onNodeWithContentDescription("全部标为已读").assertDoesNotExist()
        compose.onNodeWithText("不错过每一份回应").assertDoesNotExist()
        compose.onNodeWithText("点击登录").assertIsDisplayed()
        capture("guest-notifications-light.png", "guest-notifications")
        compose.runOnIdle { assertEquals(0, writes.get()) }
    }

    @Test fun narrowDarkLargeTextKeepsLoginReachableAndExplanationComplete() {
        var logins = 0
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.width(280.dp).height(280.dp).testTag("notifications-narrow")) {
                        NotificationsScreen(vm, { logins++ }, { _, _ -> })
                    }
                }
            }
        }
        compose.onNodeWithText("登录后查看回复、提及和点赞通知").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("登录后查看回复、提及和点赞通知")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse(layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineRight(line) <= layout.size.width + 1f)
        }
        capture("guest-notifications-dark-narrow-large.png", "notifications-narrow")
        compose.onNodeWithText("点击登录").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, logins); assertEquals(0, writes.get()) }
    }

    private fun capture(name: String, tag: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
