package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.media.*
import com.kudo.android.ui.account.*
import com.kudo.android.ui.theme.KudoTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

class UpdateDownloadUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("update-download-fixture", 0)
    private val fixture = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = prefs
    }
    private val server = MockWebServer()
    private val channel = "update-download-fixture"
    private lateinit var manager: NativeDownloadManager
    private val installs = mutableListOf<ApkInstallRequest>()
    private var external = ""
    private val apkBytes = "fixture apk bytes".repeat(16_384)
    private lateinit var owner: Owner
    private val release get() = GitHubRelease("1.2.3", "新版", "- 应用内下载更新", "2026-10-14", "$KudoSourceUrl/releases/tag/v1.2.3",
        GitHubApk("app.apk", server.url("/app.apk").toString(), apkBytes.length.toLong()))

    @Before fun setup() {
        prefs.edit().clear().commit(); server.start()
        manager = NativeDownloadManager(fixture, OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(), object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) {}
        }, "UpdateFixture/1", notificationChannel = channel)
        compose.runOnIdle { owner = Owner() }
    }
    @After fun cleanup() {
        manager.tasks.value.forEach { manager.cancel(it.id); manager.remove(it.id, deleteFile = true) }
        server.shutdown(); prefs.edit().clear().commit()
        context.getSystemService(android.app.NotificationManager::class.java).deleteNotificationChannel(channel)
    }
    private fun show(value: GitHubRelease = release) {
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalApkInstall provides { installs.add(it) }) {
            KudoTheme(DisplaySettings()) { UpdateDownloadEnvironment(manager, { external = it }) {
                Box(Modifier.fillMaxSize()) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background))
                    val visible = remember { MutableTransitionState(true) }
                    UpdateSheet(visible, value, backdrop, { external = it }) { visible.targetState = false }
                }
            } }
        } }
    }

    @Test fun nativeDownloadShowsProgressAndCompletesIntoOneInstallRequest() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(apkBytes)
            .throttleBody(32 * 1024, 250, TimeUnit.MILLISECONDS))
        show()
        compose.onNodeWithText("下载更新").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { manager.tasks.value.singleOrNull()?.bytes?.let { it > 0 } == true }
        compose.onNode(hasText("正在下载", substring = true)).assertIsDisplayed()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }.resolve("update-native-progress.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.waitUntil(10000) { installs.size == 1 }
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("", external); assertEquals(1, server.requestCount)
            assertEquals(DownloadStatus.COMPLETED, installs.single().task.status)
            assertEquals(release.apk, installs.single().release!!.apk)
        }
        compose.onNodeWithText("安装更新").performClick()
        compose.waitUntil(5000) { installs.size == 2 }
        compose.runOnIdle { assertEquals(1, server.requestCount); assertEquals(1, manager.tasks.value.size) }
    }

    @Test fun failureAndCancellationOfferNativeRetry() {
        server.enqueue(MockResponse().setResponseCode(500))
        show()
        compose.onNodeWithText("下载更新").performClick()
        compose.waitUntil(5000) { manager.tasks.value.singleOrNull()?.status == DownloadStatus.FAILED }
        compose.onNodeWithText("下载失败（HTTP 500）").assertIsDisplayed()
        server.enqueue(MockResponse().setBody(apkBytes).setBodyDelay(2, TimeUnit.SECONDS))
        compose.onNodeWithText("重新下载更新").performClick()
        compose.onNodeWithText("取消下载").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { manager.tasks.value.singleOrNull()?.status == DownloadStatus.CANCELED }
        compose.onNodeWithText("已取消").assertIsDisplayed()
        server.enqueue(MockResponse().setBody(apkBytes))
        compose.onNodeWithText("重新下载更新").performClick()
        compose.waitUntil(10000) { installs.size == 1 }
        compose.runOnIdle { assertEquals("", external); assertEquals(1, manager.tasks.value.size) }
    }

    @Test fun backgroundCompletionWaitsForForegroundAndDoesNotReinstallOnNextResume() {
        server.enqueue(MockResponse().setBody(apkBytes).setBodyDelay(1, TimeUnit.SECONDS))
        show()
        compose.onNodeWithText("下载更新").performClick()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        compose.waitUntil(5000) { manager.tasks.value.singleOrNull()?.status == DownloadStatus.COMPLETED }
        compose.runOnIdle { assertTrue(installs.isEmpty()); owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) { installs.size == 1 }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, installs.size); assertEquals(1, server.requestCount) }
    }

    @Test fun missingAssetExplainsAvailabilityAndOnlyExplicitLinkOpensRelease() {
        show(release.copy(apk = null))
        compose.onNodeWithText("此版本暂无可用安装包").assertIsDisplayed()
        compose.onNodeWithText("下载更新").assertDoesNotExist()
        compose.runOnIdle { assertTrue(manager.tasks.value.isEmpty()); assertEquals("", external) }
        compose.onNodeWithText("在 GitHub 查看此版本").performClick()
        compose.runOnIdle { assertEquals(release.url, external) }
    }

    @Test fun installerPermissionDenialCanRetryAndGrantContinuesWithoutPreparingAgain() {
        var preparations = 0
        var permissions = 0
        var launched = 0
        var allowed = false
        val messages = mutableListOf<String>()
        val model = ApkInstallViewModel { preparations++; PreparedApk("content://fixture/a.apk") }
        try {
            compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ObserveApkInstallation(model, { allowed }, { permissions++ }, { launched++ }, messages::add) {}
            } }
            val request = ApkInstallRequest(DownloadTask("id", "", "app.apk", DownloadStatus.COMPLETED))
            compose.runOnIdle { model.install(request) }
            compose.waitUntil(5000) { permissions == 1 }
            compose.runOnIdle { model.permissionResult(false) }
            compose.waitUntil(5000) { messages.size == 1 }
            compose.runOnIdle { assertEquals(0, launched); model.install(request) }
            compose.waitUntil(5000) { permissions == 2 }
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED; allowed = true; model.permissionResult(true) }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(0, launched); owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(5000) { launched == 1 }
            compose.runOnIdle { assertEquals(2, preparations); assertEquals(2, permissions) }
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun shortDarkLargeTextKeepsCompletedInstallActionVisibleWithLongNotes() {
        server.enqueue(MockResponse().setBody(apkBytes))
        val id = manager.enqueue(release.apk!!.url)!!
        compose.waitUntil(5000) { manager.tasks.value.first { it.id == id }.status == DownloadStatus.COMPLETED }
        compose.setContent { CompositionLocalProvider(LocalApkInstall provides { installs.add(it) }) {
            KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) { Box(Modifier.fillMaxSize()) {
                Box(Modifier.width(320.dp).height(360.dp)) { UpdateDownloadEnvironment(manager, { external = it }) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background))
                    val visible = remember { MutableTransitionState(true) }
                    UpdateSheet(visible, release.copy(notes = (1..60).joinToString("\n") { "- 更新内容 $it" }), backdrop, {}) { visible.targetState = false }
                } }
            } }
        } }
        val install = compose.onNodeWithText("安装更新").assertIsDisplayed()
        compose.onNodeWithText("重新下载").assertIsDisplayed()
        val before = install.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("• 更新内容 60").performScrollTo().assertIsDisplayed()
        assertEquals(before, install.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "ui-tests").resolve("update-native-short-dark.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        install.performClick()
        compose.waitUntil(5000) { installs.size == 1 }
    }

    @Test fun manualCheckUsesNativeDownloadAndDownloadManagementCanInstallApk() {
        var downloadsPage by mutableStateOf(false)
        val update = release
        server.enqueue(MockResponse().setBody(apkBytes).setHeader("Content-Type", "application/octet-stream"))
        compose.setContent { CompositionLocalProvider(LocalApkInstall provides { installs.add(it) }) {
            KudoTheme(DisplaySettings()) { UpdateDownloadEnvironment(manager, { external = it }) {
                if (downloadsPage) DownloadsScreen(manager) {}
                else AboutScreen(openExternal = { external = it }, checkUpdate = { update }) {}
            } }
        } }
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithText("下载更新").assertIsDisplayed()
        val diagnostic = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "ui-tests").resolve("update-native-manual.png")
            .outputStream().use { diagnostic.compress(Bitmap.CompressFormat.PNG, 100, it) }
        diagnostic.recycle()
        compose.onNodeWithText("下载更新").performClick()
        compose.waitUntil(5000) { installs.size == 1 }
        compose.runOnIdle { assertEquals("", external); downloadsPage = true }
        compose.onNodeWithText("安装").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, installs.size); assertNull(installs.last().release); assertEquals(1, server.requestCount) }
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    }
}
