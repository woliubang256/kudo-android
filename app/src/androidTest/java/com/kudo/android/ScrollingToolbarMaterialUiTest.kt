package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import com.kudo.android.data.*
import com.kudo.android.ui.account.*
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(coil3.annotation.DelicateCoilApi::class)
class ScrollingToolbarMaterialUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val writes = AtomicInteger()
    private var account: AccountViewModel? = null
    private var http: OkHttpClient? = null
    private var loader: ImageLoader? = null
    private lateinit var previousLoader: ImageLoader

    @Before fun edgeToEdge() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        previousLoader = SingletonImageLoader.get(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun release() {
        account?.viewModelScope?.cancel()
        http?.dispatcher?.executorService?.shutdown()
        SingletonImageLoader.setUnsafe(previousLoader)
        loader?.shutdown()
    }

    @Test fun directoryListsScrollBehindTheSelectedToolbarMaterial() {
        val categories = (0 until 40).map { Category(100 + it, "采样类别$it", "sample-$it", "0000ff", "滚动内容应经过顶部玻璃后方", 1, "code") }
        val tags = (0 until 60).map { DirectoryTag(TopicTag("采样标签$it", icon = "code", color = "#0000ff")) }
        val state = CommunityDirectoryState(accountId = 7,
            directory = CommunityDirectory(7, "fixture", categories, categories, tags, true),
            tagGroups = listOf(DirectoryTagGroup("", tags)))
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        var generation by mutableIntStateOf(0)
        compose.setContent { KudoTheme(settings) {
            key(generation) { CommunityDirectoryScreen(state, {}, {}, {}, {}, {}, {}) }
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (glass in GlassMode.entries) {
                for (tab in listOf("类别", "标签")) {
                    for (expanded in listOf(false, true)) {
                        compose.runOnIdle { settings = settings.copy(theme = theme, glass = glass); generation++ }
                        if (tab == "标签") compose.onNodeWithText(tab).performClick()
                        val list = if (tab == "类别") "category-directory-list" else "directory-tag-grid"
                        if (expanded) {
                            compose.onNodeWithTag(list).performScrollToNode(hasText("所有$tab"))
                            compose.onNodeWithText("所有$tab").performClick()
                        }
                        val sample = if (tab == "类别") compose.onNodeWithText("采样类别4") else compose.onNodeWithTag("directory-tag-采样标签4")
                        assertScrollingMaterial(list, sample, "directory-toolbar-material", "directory-content-backdrop", glass,
                            "directory-${theme.name}-${glass.name}-${if (tab == "类别") "categories" else "tags"}-$expanded")
                    }
                }
            }
        }
    }

    @Test fun notificationListsScrollBehindTheSelectedToolbarMaterialWithoutWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    if (!data.toString().startsWith("https://toolbar-material.test/")) return null
                    return object : Fetcher {
                        override suspend fun fetch(): ImageFetchResult {
                            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.BLUE)
                            return ImageFetchResult(bitmap.asImage(), false, DataSource.MEMORY)
                        }
                    }
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader!!)
        val notifications = (1..40).joinToString(",") {
            """{"id":$it,"notification_type":2,"read":true,"acting_user_avatar_template":"https://toolbar-material.test/blue.png","data":{"title":"通知采样夹具$it","display_username":"fixture"}}"""
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method != "GET") writes.incrementAndGet()
            val body = when (request.url.encodedPath) {
                "/session/current.json" -> """{"current_user":{"id":42,"username":"fixture"}}"""
                "/notifications" -> """{"notifications":[$notifications],"total_rows_notifications":40}"""
                else -> error("Unexpected fixture endpoint ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        http = client
        val repository = CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) {}
        }, "https://toolbar-material.test/".toHttpUrl(), http = client))
        val model = AccountViewModel(repository, {}, {})
        account = model
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        var visible by mutableStateOf(false)
        compose.setContent { KudoTheme(settings) {
            if (visible) NotificationsScreen(model, {}, { _, _ -> }) else Box(Modifier.fillMaxSize())
        } }
        compose.waitUntil(5000) { model.user != null }
        compose.runOnIdle { model.loadNotices() }
        compose.waitUntil(5000) { model.noticesLoaded }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            for (glass in GlassMode.entries) {
                compose.runOnIdle { settings = settings.copy(theme = theme, glass = glass); visible = true }
                for (group in listOf("全部" to "ALL", "回复" to "REPLIES")) {
                    compose.onNodeWithText(group.first, useUnmergedTree = true).performClick()
                    assertScrollingMaterial("notice-group-${group.second}", compose.onNodeWithText("通知采样夹具5"), "notifications-toolbar-material", "notifications-content-backdrop", glass,
                        "notifications-${theme.name}-${glass.name}-${group.second}")
                }
                compose.runOnIdle { assertEquals(0, writes.get()); visible = false }
            }
        }
    }

    private fun assertScrollingMaterial(listTag: String, sample: SemanticsNodeInteraction, toolbarTag: String, sourceTag: String, glass: GlassMode, name: String) {
        val list = compose.onNodeWithTag(listTag)
        list.performScrollToIndex(0)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag(toolbarTag).assertIsDisplayed()
            .assert(!hasAnyAncestor(hasTestTag(sourceTag))).fetchSemanticsNode().boundsInRoot.copy(top = root.top)
        val before = toolbarBlue(toolbar)
        list.performScrollToIndex(4)
        val distance = sample.fetchSemanticsNode().boundsInRoot.center.y - (toolbar.top + toolbar.height * .55f)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, distance) }
        val after = toolbarBlue(toolbar)
        capture("$name-scrolled.png")
        println("Toolbar live blue: $name before=$before after=$after viewport=${list.fetchSemanticsNode().boundsInRoot} toolbar=$toolbar")
        assertTrue("滑动后的顶部应采样真实蓝色内容而非占位背景：$name before=$before after=$after",
            after - before > if (glass == GlassMode.TRANSLUCENT) .015f else .08f)
        assertEquals("列表视口应延伸到状态栏与工具栏背后", root.top, list.fetchSemanticsNode().boundsInRoot.top, 1f)
        list.performScrollToIndex(0)
        assertEquals("回到顶部后应恢复没有条目经过的背景", before, toolbarBlue(toolbar), .035f)
    }

    private fun toolbarBlue(toolbar: Rect): Float {
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        var strongest = -1f
        for (vertical in (toolbar.top.toInt() + 4) until (toolbar.bottom.toInt() - 4) step 3) {
            for (horizontal in (toolbar.left.toInt() + 4) until (toolbar.right.toInt() - 4) step 3) {
                val color = pixels[horizontal, vertical]
                strongest = maxOf(strongest, color.blue - color.red)
            }
        }
        return strongest
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
