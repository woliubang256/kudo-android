package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.media.*
import com.kudo.android.ui.account.*
import com.kudo.android.ui.theme.KudoTheme
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.Proxy

class DownloadsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun guestAndMemberPlaceDownloadManagementBelowSettings() {
        var member by mutableStateOf<Member?>(null)
        var downloads = 0
        compose.setContent {
            KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
                Box(Modifier.width(280.dp)) { ProfileScreen(member, {}, {}, {}, {}, downloads = { downloads++ }) }
            }
        }
        fun checkOrder() {
            compose.onNodeWithText("下载管理").performScrollTo().assertIsDisplayed()
            val settings = compose.onNodeWithText("设置").fetchSemanticsNode().boundsInRoot
            val entry = compose.onNodeWithText("下载管理").fetchSemanticsNode().boundsInRoot
            assertTrue(entry.top > settings.top)
            compose.onNodeWithText("下载管理").performClick()
        }
        checkOrder()
        compose.runOnIdle { member = Member(42, "fixture", "下载入口测试", "") }
        checkOrder()
        compose.runOnIdle { assertEquals(2, downloads) }
    }

    @Test fun taskPageShowsFailureRetryCompletionAndRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("download-ui-fixture", 0)
        prefs.edit().clear().commit()
        val fixture = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        val server = MockWebServer()
        server.start()
        val manager = NativeDownloadManager(fixture, OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) {}
        }, "DownloadFixture/1", notificationChannel = "download-ui-fixture")
        var uri: String? = null
        try {
            compose.setContent { KudoTheme(DisplaySettings()) { DownloadsScreen(manager) {} } }
            compose.onNodeWithText("暂无下载任务").assertIsDisplayed()
            server.enqueue(MockResponse().setResponseCode(403))
            manager.enqueue(server.url("/key-labyrinth-20261013.zip").toString())
            compose.waitUntil(5000) { manager.tasks.value.singleOrNull()?.status == DownloadStatus.FAILED }
            compose.onNodeWithText("下载失败（HTTP 403）").assertIsDisplayed()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody("fixture bytes"))
            compose.onNodeWithText("重试").performClick()
            compose.waitUntil(5000) { manager.tasks.value.singleOrNull()?.status == DownloadStatus.COMPLETED }
            uri = manager.tasks.value.single().fileUri
            compose.onNodeWithText("下载完成").assertIsDisplayed()
            compose.onNodeWithText("打开").assertIsDisplayed()
            val image = compose.onNodeWithTag("downloads-screen").captureToImage().asAndroidBitmap()
            File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }.resolve("downloads-completed.png")
                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            compose.onNodeWithContentDescription("删除下载记录").performClick()
            compose.onNodeWithText("暂无下载任务").assertIsDisplayed()
        } finally {
            manager.tasks.value.forEach { manager.cancel(it.id) }
            uri?.let { context.contentResolver.delete(android.net.Uri.parse(it), null, null) }
            server.shutdown()
            prefs.edit().clear().commit()
            context.getSystemService(android.app.NotificationManager::class.java).deleteNotificationChannel("download-ui-fixture")
        }
    }
}
