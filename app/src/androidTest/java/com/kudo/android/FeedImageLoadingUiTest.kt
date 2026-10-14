package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.Options
import com.kudo.android.data.*
import com.kudo.android.ui.components.TopicCard
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.CompletableDeferred
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class FeedImageLoadingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previous: ImageLoader
    private lateinit var loader: ImageLoader
    private val first = CompletableDeferred<Unit>()
    private val retry = CompletableDeferred<Unit>()
    private val replacement = CompletableDeferred<Unit>()
    private val requests = CopyOnWriteArrayList<String>()
    private var failFirst = false
    private var firstFailure: Throwable = IOException("fixture offline")
    private var opened = 0
    private val wide = "https://feed-image-loading.test/wide.png"
    private val ordinary = "https://feed-image-loading.test/ordinary.png"
    private val topic = Topic(51, "列表封面加载测试", "加载期间保留列表位置", null, 1,
        emptyList(), 2, 30, 1, wide, "2026-10-12T00:00:00Z")

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        previous = SingletonImageLoader.get(context)
        val pictures = mapOf(wide to png(400, 80), ordinary to png(80, 60))
        loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    val url = data.toString()
                    val bytes = pictures[url] ?: return null
                    return object : Fetcher {
                        override suspend fun fetch(): SourceFetchResult {
                            requests += url
                            val attempt = requests.count { it == url }
                            when {
                                url == ordinary -> replacement.await()
                                attempt == 1 -> first.await()
                                else -> retry.await()
                            }
                            if (failFirst && url == wide && attempt == 1) throw firstFailure
                            return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem),
                                "image/png", DataSource.MEMORY)
                        }
                    }
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun cleanup() {
        first.complete(Unit); retry.complete(Unit); replacement.complete(Unit)
        SingletonImageLoader.setUnsafe(previous)
        loader.shutdown()
    }

    private fun show(settings: DisplaySettings = DisplaySettings(theme = ThemeMode.LIGHT), image: () -> String = { wide }) {
        compose.setContent { KudoTheme(settings) { Column(Modifier.fillMaxWidth()) {
            TopicCard(topic.copy(image = image()), null, { opened++ }, {})
        } } }
    }

    private fun awaitLoaded() = compose.waitUntil(5000) {
        compose.onAllNodesWithTag("topic-image-51-loading", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }

    @Test fun delayedImageShowsLoadingWithoutMovingTheListAndThenOpensTopic() {
        show()
        compose.waitUntil(5000) { requests.size == 1 }
        compose.onNodeWithTag("topic-image-51-loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("image-aspect-badge", useUnmergedTree = true).assertDoesNotExist()
        val bounds = compose.onNodeWithTag("topic-image-51").fetchSemanticsNode().boundsInRoot
        capture("feed-image-loading-light.png")
        compose.onNodeWithTag("topic-image-51").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, requests.size) }
        first.complete(Unit)
        awaitLoaded()
        compose.onNodeWithText("加载失败，点击重试", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("宽图", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(bounds, compose.onNodeWithTag("topic-image-51").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("topic-image-51").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, opened); assertEquals(1, requests.size) }
    }

    @Test fun failedImageShowsHttpCodeAndReasonBelowRetryAndClearsThemWhenRetrying() {
        failFirst = true
        firstFailure = HttpException(NetworkResponse(code = 503))
        show(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f))
        first.complete(Unit)
        compose.waitUntil(5000) { compose.onAllNodesWithText("加载失败，点击重试", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val retryText = compose.onNodeWithText("加载失败，点击重试", useUnmergedTree = true)
        val details = compose.onNodeWithText("HTTP 503 · 服务暂不可用", useUnmergedTree = true)
        details.assertIsDisplayed()
        assertTrue(details.fetchSemanticsNode().boundsInRoot.top >= retryText.fetchSemanticsNode().boundsInRoot.bottom)
        capture("feed-image-http-error-dark.png")
        details.performTouchInput { click() }
        compose.waitUntil(5000) { requests.size == 2 }
        details.assertDoesNotExist()
        compose.onNodeWithTag("topic-image-51-loading", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, opened) }
        retry.complete(Unit)
        awaitLoaded()
        details.assertDoesNotExist()
        compose.onNodeWithTag("topic-image-51").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened); assertEquals(2, requests.size) }
    }

    @Test fun failedImageShowsWrappedNativeCodeAndReasonInLightTheme() {
        failFirst = true
        firstFailure = IOException("wrapper", UnknownHostException("DoH: dns_timeout"))
        show()
        first.complete(Unit)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("topic-image-51-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("DoH dns_timeout · DoH 查询超时", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("topic-image-51-loading", useUnmergedTree = true).assertDoesNotExist()
        capture("feed-image-native-error-light.png")
        compose.runOnIdle { assertEquals(0, opened); assertEquals(1, requests.size) }
    }

    @Test fun changingFailedUrlClearsDiagnosticAndUnknownErrorsDoNotExposeRawSecrets() {
        var image by mutableStateOf(wide)
        failFirst = true
        firstFailure = IllegalStateException("https://private.example/image?signature=secret Cookie: session=secret")
        show(image = { image })
        first.complete(Unit)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("topic-image-51-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("IllegalStateException · 图片加载失败，原因未识别", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(hasText("secret", substring = true), useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { image = ordinary }
        compose.waitUntil(5000) { requests.contains(ordinary) }
        compose.onNodeWithTag("topic-image-51-error", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("topic-image-51-loading", useUnmergedTree = true).assertIsDisplayed()
        replacement.complete(Unit)
        awaitLoaded()
        compose.onNodeWithTag("topic-image-51-error", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, opened) }
    }

    @Test fun failedImageStopsLoadingAndRetryWaitsForAnotherRequestWithoutOpeningTopic() {
        failFirst = true
        show(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f))
        compose.waitUntil(5000) { requests.size == 1 }
        capture("feed-image-loading-dark.png")
        first.complete(Unit)
        compose.waitUntil(5000) { compose.onAllNodesWithText("加载失败，点击重试", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("topic-image-51-loading", useUnmergedTree = true).assertDoesNotExist()
        capture("feed-image-error-dark.png")
        compose.onNodeWithText("加载失败，点击重试", useUnmergedTree = true).performTouchInput { click() }
        compose.waitUntil(5000) { requests.size == 2 }
        compose.onNodeWithTag("topic-image-51-loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("加载失败，点击重试", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, opened) }
        retry.complete(Unit)
        awaitLoaded()
        compose.onNodeWithText("宽图", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("topic-image-51").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened); assertEquals(2, requests.size) }
    }

    @Test fun changingUrlDropsPreviousAspectBadgeWhileNewImageLoads() {
        var image by mutableStateOf(wide)
        first.complete(Unit)
        show(image = { image })
        compose.waitUntil(5000) { compose.onAllNodesWithText("宽图", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { image = ordinary }
        compose.waitUntil(5000) { requests.contains(ordinary) }
        compose.onNodeWithTag("topic-image-51-loading", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("image-aspect-badge", useUnmergedTree = true).assertDoesNotExist()
        replacement.complete(Unit)
        awaitLoaded()
        compose.onNodeWithTag("image-aspect-badge", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, opened); assertEquals(2, requests.size) }
    }

    @Test fun changingFailedUrlClearsTheErrorAndTitleStillOpensTopic() {
        var image by mutableStateOf(wide)
        failFirst = true
        first.complete(Unit)
        show(image = { image })
        compose.waitUntil(5000) { compose.onAllNodesWithText("加载失败，点击重试", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(topic.title, useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened); image = ordinary }
        compose.waitUntil(5000) { requests.contains(ordinary) }
        compose.onNodeWithText("加载失败，点击重试", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("topic-image-51-loading", useUnmergedTree = true).assertIsDisplayed()
        replacement.complete(Unit)
        awaitLoaded()
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff46c598.toInt()) }
        return try { ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("feedImageScreenshots") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
