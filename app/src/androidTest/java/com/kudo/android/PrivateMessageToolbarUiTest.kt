package com.kudo.android

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.PrivateMessageScreen
import com.kudo.android.ui.theme.KudoTheme
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PrivateMessageToolbarUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val member = Member(Int.MIN_VALUE + 2, "toolbar_fixture", "", "")
    private val draftKey = privateMessageDraftKey(member.id, "author")
    private val requests = CopyOnWriteArrayList<String>()
    private val holdUpload = AtomicBoolean()
    private val failUpload = AtomicBoolean()
    private val uploadStarted = CountDownLatch(1)
    private val releaseUpload = CountDownLatch(1)
    private lateinit var settings: AppSettings
    private lateinit var repository: CommunityRepository
    private lateinit var http: OkHttpClient
    private lateinit var image: File
    private lateinit var imageUri: Uri
    private var backs = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <Input, Output> onLaunch(requestCode: Int, contract: ActivityResultContract<Input, Output>, input: Input,
            options: ActivityOptionsCompat?) {
            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(imageUri))
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }

    @Before fun fixture() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val context = instrumentation.targetContext
        settings = AppSettings(context)
        image = File(context.cacheDir, "image_exports/private-message-toolbar-fixture.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        imageUri = FileProvider.getUriForFile(context, "${context.packageName}.image-files", image)
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            requests += "${request.method} $path"
            val body = when (path) {
                "/latest" -> """<script id="data-preloaded" type="application/json">{"siteSettings":{"min_personal_message_post_length":3,"max_post_length":64000}}</script>"""
                "/session/csrf" -> """{"csrf":"fixture"}"""
                "/uploads.json" -> {
                    uploadStarted.countDown()
                    if (holdUpload.get()) check(releaseUpload.await(10, TimeUnit.SECONDS))
                    if (failUpload.get()) """{"errors":["测试上传失败"]}""" else """{"short_url":"upload://fixture.png"}"""
                }
                else -> error("Unexpected private-message toolbar request: ${request.method} $path")
            }
            val failed = path == "/uploads.json" && failUpload.get()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (failed) 422 else 200).message("fixture")
                .body(body.toResponseBody("text/html".toMediaType())).build()
        }.build()
        repository = CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) = Unit
        }, http = http))
    }

    @After fun clean() {
        releaseUpload.countDown()
        settings.clearDraft(draftKey)
        image.delete()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun show(raw: String = "前中文后", dark: Boolean = false) {
        settings.saveDraft(draftKey, Draft("私信标题", raw, recipients = "author"))
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                KudoTheme(DisplaySettings(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, fontScale = if (dark) 1.3f else 1f)) {
                    PrivateMessageScreen(member, repository, settings, { backs++ }, { error("The fixture must not send a private message") }, recipient = "author")
                }
            }
        }
        compose.waitUntil(5000) { requests.contains("GET /latest") }
    }

    private fun tool(label: String) = compose.onNodeWithContentDescription(label).performScrollTo().performClick()

    @Test fun privateMessageUsesSharedFormattingAndPreviewWithoutSending() {
        show()
        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(1, 3))
        tool("粗体")
        compose.onNodeWithTag("editor-body").assertTextEquals("前**中文**后")
        compose.runOnIdle { assertEquals("前**中文**后", settings.draft(draftKey).raw) }
        tool("预览正文")
        compose.onNodeWithTag("markdown-preview").assertIsDisplayed()
        compose.waitUntil(5000) { compose.onAllNodesWithText("前中文后").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("关闭预览").performClick()
        compose.onNodeWithTag("editor-body").assertTextEquals("前**中文**后")
        compose.onNodeWithTag("editor-body").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3, 5)))
        compose.runOnIdle { assertEquals(listOf("GET /latest"), requests.toList()) }
    }

    @Test fun linksListsAndEmojiWorkWithTheDarkLargeTextKeyboardLayout() {
        show(dark = true)
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(1, 3))
        tool("插入链接")
        compose.onNodeWithTag("editor-link-url").performTextInput("linux.do")
        compose.onNodeWithText("插入", substring = false).performClick()
        compose.waitUntil(5000) { settings.draft(draftKey).raw == "前[中文](https://linux.do)后" }
        tool("列表")
        compose.onNodeWithText("任务列表").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("任务列表").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertTrue(settings.draft(draftKey).raw.startsWith("- [ ] ")) }
        tool("表情")
        compose.onNodeWithTag("editor-emoji-search").performTextInput("smile")
        compose.onNodeWithContentDescription(":smile:").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("editor-emoji-search").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertTrue(settings.draft(draftKey).raw.contains(":smile:")) }
        compose.onNodeWithTag("editor-body").performClick()
        compose.waitUntil(5000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNodeWithTag("editor-toolbar").assertIsDisplayed()
        compose.onNodeWithText("发送").assertIsDisplayed().assertIsEnabled()
        capture("private-message-toolbar-dark-keyboard.png")
        compose.runOnIdle { assertEquals(listOf("GET /latest"), requests.toList()) }
    }

    @Test fun imageUploadRebasesItsAnchorAndBlocksSendingAndLeavingUntilItFinishes() {
        holdUpload.set(true)
        show("前面后")
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(2))
        tool("上传图片")
        compose.waitUntil(5000) { uploadStarted.count == 0L }
        compose.onNodeWithText("发送").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(0, backs) }
        compose.onNodeWithTag("editor-body").performTextReplacement("前面新增后")
        releaseUpload.countDown()
        compose.waitUntil(5000) { settings.draft(draftKey).raw.contains("upload://fixture.png") }
        compose.onNodeWithTag("editor-body").assertTextEquals("前面新增\n\n![图片](upload://fixture.png)\n\n后")
        compose.onNodeWithText("发送").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, requests.count { it == "POST /uploads.json" })
            assertFalse(requests.any { it.contains("/posts.json") })
        }
    }

    @Test fun failedUploadKeepsTheDraftAndUnlocksTheEditor() {
        failUpload.set(true)
        show("前面后")
        tool("上传图片")
        compose.waitUntil(5000) { compose.onAllNodesWithText("测试上传失败").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-body").assertTextEquals("前面后")
        compose.onNodeWithText("发送").assertIsEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle {
            assertEquals(1, backs)
            assertEquals("前面后", settings.draft(draftKey).raw)
            assertEquals(1, requests.count { it == "POST /uploads.json" })
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
