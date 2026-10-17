package com.kudo.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.TopicScreen
import com.kudo.android.ui.topic.TopicState
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class TopicTitleTextUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previousLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private val loaded = CopyOnWriteArrayList<String>()
    private val requested = CopyOnWriteArrayList<String>()
    private val directoryRequests = AtomicInteger()
    private val fire = "https://linux.do/images/emoji/twemoji/fire.png?v=15"
    private val title = ":fire:【省钱百科19】Gemini :custom:"
    private val topic = Topic(3, title, "", null, 0, emptyList(), 0, 0, 0, "", "")

    @Before fun fixtureImages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        previousLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    val url = data.toString()
                    if (!url.startsWith("https://emoji.test/") && !url.startsWith("https://linux.do/images/emoji/")) return null
                    return object : Fetcher {
                        override suspend fun fetch(): SourceFetchResult {
                            requested += url
                            if ("missing" in url) throw IOException("fixture: image unavailable")
                            val bytes = instrumentation.context.assets.open("avatars/static.png").use { it.readBytes() }
                            return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), "image/png", DataSource.MEMORY)
                        }
                    }
                }
            })
        }.eventListener(object : EventListener() {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { loaded += request.data.toString() }
        }).build()
        SingletonImageLoader.setUnsafe(loader)
    }

    @After fun restoreImages() { SingletonImageLoader.setUnsafe(previousLoader); loader.shutdown() }

    @Composable private fun Environment(settings: DisplaySettings = DisplaySettings(), content: @Composable () -> Unit) {
        val scope = rememberCoroutineScope()
        val cache = remember { AvatarMetadataCache(scope, { emptyList() }, {
            directoryRequests.incrementAndGet()
            mapOf("custom" to "https://emoji.test/custom.png", "missing" to "https://emoji.test/missing.png")
        }) }
        DisposableEffect(cache) { onDispose { cache.close() } }
        KudoTheme(settings) { CompositionLocalProvider(LocalAvatarMetadata provides cache, content = content) }
    }

    @Test fun feedTraditionalAndPinnedTitlesShareInlineEmojiAndKeepTheirClickActions() {
        val clicked = mutableListOf<Int>()
        compose.setContent { Environment {
            Column {
                TopicCard(topic, null, { clicked += 1 }, {})
                TopicTitleRow(topic.copy(id = 4), null, { clicked += 2 }, {})
                CompactPinnedTopicRow(topic.copy(id = 5, pinned = true), null, { clicked += 3 })
            }
        } }
        compose.waitUntil(5000) { loaded.contains(fire) && loaded.contains("https://emoji.test/custom.png") }
        compose.onAllNodesWithContentDescription(":fire:", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithContentDescription(":custom:", useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { index ->
            assertEquals(2, layout(compose.onAllNodesWithText(title, useUnmergedTree = true)[index]).placeholderRects.size)
        }
        compose.onNodeWithTag("topic-card-3").performClick()
        compose.onNodeWithTag("topic-title-4").performClick()
        compose.onNodeWithTag("topic-pinned-5").performClick()
        assertEquals(listOf(1, 2, 3), clicked)
        assertEquals(1, directoryRequests.get())
        capture("topic-title-emoji-lists.png")
    }

    @Test fun detailHeaderRendersItsTitleEmoji() {
        val author = Member(2, "author", "测试作者", "", avatarDetailsLoaded = true)
        val post = Post(10, 1, author, "<p>正文</p>", "2026-10-18T00:00:00Z", 0, false, null, null, true)
        val detail = TopicDetail(3, title, 0, listOf(post), listOf(10), false)
        compose.setContent { Environment {
            TopicScreen(TopicState(detail, false), GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.waitUntil(5000) { loaded.contains(fire) && loaded.contains("https://emoji.test/custom.png") }
        val result = layout(compose.onNodeWithText(title, useUnmergedTree = true))
        assertEquals(2, result.placeholderRects.filterNotNull().size)
        compose.onNodeWithContentDescription(":custom:", useUnmergedTree = true).assertIsDisplayed()
        capture("topic-title-emoji-detail.png")
    }

    @Test fun inlineSizeScalesWithTextAndOneLineTitlesStillEllipsize() {
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        val longTitle = ":fire: " + "需要省略的长标题".repeat(12)
        compose.setContent { Environment(settings) {
            TopicTitleText(longTitle, Modifier.width(220.dp), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } }
        compose.waitUntil(5000) { loaded.contains(fire) }
        val before = layout(compose.onNodeWithText(longTitle, useUnmergedTree = true))
        compose.runOnIdle { settings = settings.copy(theme = ThemeMode.DARK, fontScale = 1.3f) }
        val after = layout(compose.onNodeWithText(longTitle, useUnmergedTree = true))
        assertEquals(1, after.lineCount)
        assertTrue(after.isLineEllipsized(0))
        val emoji = after.placeholderRects.single()!!
        assertEquals(1.3f, emoji.width / before.placeholderRects.single()!!.width, .06f)
        assertTrue(emoji.height > 0 && emoji.top >= 0 && emoji.bottom <= after.size.height)
        capture("topic-title-emoji-dark-large.png")
    }

    @Test fun failedImageReturnsToLiteralShortcodeAndOtherEmojiStayVisible() {
        val text = ":missing: 标题 :fire:"
        compose.setContent { Environment { TopicTitleText(text) } }
        compose.waitUntil(5000) {
            requested.contains("https://emoji.test/missing.png") && loaded.contains(fire) &&
                compose.onAllNodesWithContentDescription(":missing:", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        val result = layout(compose.onNodeWithText(text, useUnmergedTree = true))
        assertEquals(1, result.placeholderRects.size)
        compose.onNodeWithContentDescription(":fire:", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun standardEmojiWorkWithoutAnAccountDirectory() {
        compose.setContent { KudoTheme(DisplaySettings()) { TopicTitleText(":fire: 🔥") } }
        compose.waitUntil(5000) { loaded.contains(fire) }
        assertEquals(1, layout(compose.onNodeWithText(":fire: 🔥", useUnmergedTree = true)).placeholderRects.size)
        assertEquals(0, directoryRequests.get())
    }

    @Test fun ordinaryTitlesDoNotLoadTheEmojiDirectory() {
        val text = "🔥 10:30 <标题> &amp; https://example.com"
        compose.setContent { Environment { TopicTitleText(text) } }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(0, directoryRequests.get())
        assertTrue(requested.isEmpty())
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
}
