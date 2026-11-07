package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.*
import coil3.decode.DataSource
import coil3.fetch.*
import coil3.request.Options
import com.kudo.android.data.*
import com.kudo.android.ui.library.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

@OptIn(coil3.annotation.DelicateCoilApi::class, ExperimentalTestApi::class)
class ImageLibraryGalleryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "library_gallery_${UUID.randomUUID()}"
    private val fixture = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(preferenceName, mode)
    }
    private val store = ImageLibraryStore(fixture)
    private lateinit var previousLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private lateinit var gallery: ImageGalleryState
    private val loaded = CopyOnWriteArraySet<String>()
    private val colors = (0..19).map { android.graphics.Color.rgb(45 + it * 8, 75 + it * 6, 205 - it * 7) }
    private val defaults = (0..11).map { index -> SavedImage(id = "image-$index", originalUrl = "https://library-gallery.test/$index.png",
        alt = "收藏图片 $index", ratio = if (index == 2) 1f / 6f else 4f / 3f,
        source = ImageSource(100 + index, index + 1, "来源 $index")) }
    private val other = defaults.first().copy(id = "other-copy", folderId = "other", source = ImageSource(999, 7, "另一条收藏"))

    @Before fun setup() {
        previousLoader = SingletonImageLoader.get(base)
        loader = ImageLoader.Builder(base).memoryCache(null).diskCache(null).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                    if (!data.toString().startsWith("https://library-gallery.test/")) return null
                    return object : Fetcher {
                        override suspend fun fetch(): FetchResult {
                            val index = data.toString().substringAfterLast('/').substringBefore('.').toInt()
                            val bitmap = Bitmap.createBitmap(if (index == 2) 100 else 160, if (index == 2) 600 else 120, Bitmap.Config.ARGB_8888)
                                .apply { eraseColor(colors[index]) }
                            loaded += data.toString()
                            return ImageFetchResult(bitmap.asImage(), false, DataSource.MEMORY)
                        }
                    }
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        runBlocking { store.update(101) { ImageLibrary(folders = listOf(ImageFolder(DEFAULT_IMAGE_FOLDER, "默认收藏"), ImageFolder("other", "其他")),
            images = defaults + other) } }
    }

    @After fun cleanup() {
        compose.mainClock.autoAdvance = true
        SingletonImageLoader.setUnsafe(previousLoader)
        loader.shutdown()
        base.deleteSharedPreferences(preferenceName)
    }

    private fun show(restoration: StateRestorationTester? = null, dark: Boolean = false, narrow: Boolean = false) {
        val content: @Composable () -> Unit = {
            KudoTheme(DisplaySettings(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, fontScale = if (narrow) 1.3f else 1f)) {
                Box(Modifier.fillMaxSize()) {
                    Box(if (narrow) Modifier.width(280.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                        ImageLibraryEnvironment(store, 101, true) {
                            ImageGalleryHost({ error("A library must not load a topic album") }, {}, 101) {
                                val current = LocalImageGallery.current!!
                                SideEffect { gallery = current }
                                ImageLibraryScreen({}) {}
                            }
                        }
                    }
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("library-images").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun open(id: String) {
        compose.onNodeWithTag("library-images").performScrollToNode(hasTestTag("library-image-$id"))
        compose.onNodeWithTag("library-image-$id").performClick()
        compose.waitUntil(5000) { gallery.session != null }
        compose.waitForIdle()
    }

    private fun pageTo(index: Int) {
        while (gallery.session!!.index != index) {
            val current = gallery.session!!.index
            compose.onNodeWithTag("image-pager").performTouchInput { if (index > current) swipeLeft() else swipeRight() }
            compose.waitUntil(5000) { gallery.session!!.index != current }
        }
        compose.waitUntil(5000) { gallery.session!!.current.originalUrl in loaded }
        compose.waitForIdle()
    }

    private fun close() {
        compose.onNodeWithTag("image-viewer").performKeyInput { pressKey(Key.Escape) }
        compose.waitUntil(5000) { gallery.session == null }
    }

    @Test fun currentFolderUsesItsOwnOrderAndAllKeepsDuplicateOccurrences() {
        show()
        open("image-1")
        compose.runOnIdle { assertEquals(defaults.map { it.originalUrl }, gallery.session!!.images.map { it.originalUrl }); assertEquals(1, gallery.session!!.index) }
        pageTo(2)
        compose.onNodeWithText("3 / 12").assertIsDisplayed()
        close()
        compose.onNodeWithText("默认收藏").assertIsSelected()
        compose.onNodeWithText("其他").performClick()
        open("other-copy")
        compose.runOnIdle { assertEquals(1, gallery.session!!.images.size); assertEquals(other.source, gallery.session!!.sourceAt(0)) }
        compose.onNodeWithText("1 / 1").assertDoesNotExist()
        close()
        compose.onNodeWithText("全部").performClick()
        open("other-copy")
        compose.runOnIdle { assertEquals(13, gallery.session!!.images.size); assertEquals(12, gallery.session!!.index) }
        pageTo(0)
        compose.runOnIdle { assertEquals(defaults[0].source, gallery.session!!.sourceAt(0)) }
        close()
        compose.onNodeWithTag("library-image-image-0").assertIsDisplayed()
        compose.onNodeWithText("全部").assertIsSelected()
    }

    @Test fun croppedThumbnailExpandsAndCurrentOffscreenImageShrinksToItsOwnGridCell() {
        show()
        compose.waitUntil(5000) { defaults[0].thumbnailUrl in loaded }
        val source = compose.onNodeWithTag("library-image-image-0").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("library-image-image-0").performClick()
            awaitTransition()
            assertNear(source, coloredBounds(colors[0]), 4f)
            compose.mainClock.advanceTimeBy(80)
            assertTrue(coloredBounds(colors[0]).width > source.width)
            capture("library-gallery-opening.png")
            compose.mainClock.advanceTimeBy(600)
        } finally { compose.mainClock.autoAdvance = true }
        pageTo(10)
        val expanded = coloredBounds(colors[10])
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("image-viewer").performKeyInput { pressKey(Key.Escape) }
            awaitTransition()
            compose.runOnIdle { assertNotNull("The lazy grid must reveal the current image", gallery.hiddenAnchor?.snapshot()) }
            compose.mainClock.advanceTimeBy(80)
            assertTrue(coloredBounds(colors[10]).width < expanded.width)
            capture("library-gallery-closing.png")
            compose.mainClock.advanceTimeBy(600)
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithTag("image-viewer").assertDoesNotExist()
        val target = compose.onNodeWithTag("library-image-image-10").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertNear(target, coloredBounds(colors[10]), 4f)
        val tabs = compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot
        assertTrue("Returned thumbnail must be below the pinned category tabs", target.top >= tabs.bottom)
        capture("library-gallery-returned.png")
    }

    @Test fun duplicateUrlUsesTheCurrentRecordsSourceAndReturnsToThatRecord() {
        show(dark = true, narrow = true)
        compose.onNodeWithTag("image-library-screen").assertWidthIsEqualTo(280.dp)
        compose.onNodeWithText("全部").performClick()
        open("other-copy")
        compose.onNodeWithTag("gallery-page-12").performTouchInput { longClick() }
        compose.onNodeWithText("收藏图片", substring = false).performClick()
        compose.onNodeWithTag("collect-new-folder").performClick()
        compose.onNodeWithTag("collect-folder-name").performTextInput("验证来源")
        compose.onNodeWithText("新建并收藏").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { store.load(101).images.size == 14 } }
        val saved = runBlocking { store.load(101) }.images.first()
        assertEquals(other.originalUrl, saved.originalUrl)
        assertEquals(other.source, saved.source)
        close()
        compose.onNodeWithTag("library-image-other-copy").assertIsDisplayed()
        compose.onNodeWithTag("library-image-image-0").assertIsNotDisplayed()
        capture("library-gallery-duplicate-dark.png")
    }

    @Test fun removingTheCurrentRecordKeepsTheOpenSnapshotAndStillClosesSafely() {
        show()
        open("image-0")
        pageTo(1)
        runBlocking { store.update(101) { it.removeImage("image-1") } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(12, gallery.session!!.images.size); assertEquals(defaults[1].originalUrl, gallery.session!!.current.originalUrl) }
        close()
        compose.onNodeWithTag("image-library-screen").assertIsDisplayed()
        compose.onNodeWithTag("library-image-image-1").assertDoesNotExist()
    }

    @Test fun restoredGalleryKeepsThePageSourceAndGridReturnTarget() {
        val restoration = StateRestorationTester(compose)
        show(restoration)
        open("image-0")
        pageTo(7)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5000) { gallery.session?.index == 7 }
        compose.onNodeWithText("8 / 12").assertIsDisplayed()
        compose.runOnIdle { assertEquals(defaults[7].source, gallery.session!!.sourceAt(7)) }
        close()
        compose.onNodeWithTag("library-image-image-7").assertIsDisplayed()
        compose.onNodeWithText("默认收藏").assertIsSelected()
    }

    private fun awaitTransition() {
        repeat(70) {
            if (compose.onAllNodesWithTag("gallery-transition").fetchSemanticsNodes().isNotEmpty()) return
            compose.mainClock.advanceTimeByFrame()
            android.os.SystemClock.sleep(16)
        }
        fail("The image must animate after locating its actual grid thumbnail")
    }

    private fun coloredBounds(color: Int): Rect {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if ((pixels[y * bitmap.width + x] and 0xFFFFFF) == (color and 0xFFFFFF)) {
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        assertTrue("The current image must remain visible in actual pixels", right > left && bottom > top)
        return Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }

    private fun assertNear(expected: Rect, actual: Rect, tolerance: Float) {
        assertEquals(expected.left, actual.left, tolerance); assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.right, actual.right, tolerance); assertEquals(expected.bottom, actual.bottom, tolerance)
    }

    private fun capture(name: String) {
        File(base.getExternalFilesDir(null), name).outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
