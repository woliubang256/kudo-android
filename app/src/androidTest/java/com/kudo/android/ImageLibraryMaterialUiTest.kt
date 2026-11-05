package com.kudo.android

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.*
import coil3.decode.DataSource
import coil3.fetch.*
import coil3.request.Options
import com.kudo.android.data.*
import com.kudo.android.ui.editor.EditorToolBackdropHost
import com.kudo.android.ui.editor.EditorToolSheet
import com.kudo.android.ui.library.*
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@OptIn(coil3.annotation.DelicateCoilApi::class)
class ImageLibraryMaterialUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "library_material_${UUID.randomUUID()}"
    private val fixture = object : android.content.ContextWrapper(context) {
        override fun getSharedPreferences(preference: String, mode: Int) = super.getSharedPreferences(name, mode)
    }
    private val state = ImageLibraryState(ImageLibraryStore(fixture), 42)
    private lateinit var previousLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private val redLoads = AtomicInteger()

    @Before fun setup() {
        previousLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader) = object : Fetcher {
                    override suspend fun fetch(): FetchResult {
                        val red = data.toString().contains("red")
                        val width = if (data.toString().contains("wide")) 240 else 80
                        val height = if (data.toString().contains("tall")) 240 else 80
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(if (red) android.graphics.Color.RED else android.graphics.Color.BLUE)
                        }
                        if (red) redLoads.incrementAndGet()
                        return ImageFetchResult(bitmap.asImage(), false, DataSource.MEMORY)
                    }
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        runBlocking { state.update { library ->
            (1..40).fold(library) { updated, index -> updated.save(SavedImage(id = "image-$index",
                originalUrl = "https://material.test/$index.png", thumbnailUrl = "https://material.test/blue.png")) }
        } }
    }

    @After fun cleanup() {
        SingletonImageLoader.setUnsafe(previousLoader)
        loader.shutdown()
        context.deleteSharedPreferences(name)
    }

    @Test fun removalSheetSamplesChangingImagesAcrossThemesAndMaterialModes() = assertSheetMaterial(changeFolder = false)

    @Test fun classificationSheetSamplesChangingImagesAcrossThemesAndMaterialModes() = assertSheetMaterial(changeFolder = true)

    @Test fun pickerTitleTabsAndGridShareTheLiveSheetSurface() {
        runBlocking { state.update { it.copy(images = it.images.take(1)) } }
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT, fontScale = 1.3f))
        var source by mutableStateOf(Color(0xFFB6314D))
        compose.setContent { KudoTheme(settings) { EditorToolBackdropHost {
            Box(Modifier.fillMaxSize().background(source)) {
                CompositionLocalProvider(LocalImageLibrary provides state) {
                    EditorToolSheet("图库", {}) { ImageLibraryPicker(true) {} }
                }
            }
        } } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { settings = settings.copy(theme = theme, glass = glass); source = Color(0xFFB6314D) }
            val red = pickerSurface("$theme-$glass-red")
            compose.runOnIdle { source = Color(0xFF2564C3) }
            val blue = pickerSurface("$theme-$glass-blue")
            val difference = abs(red.red - blue.red) + abs(red.blue - blue.blue)
            val minimum = if (glass == GlassMode.TRANSLUCENT || Build.VERSION.SDK_INT < 31) .02f else .2f
            assertTrue("Picker must keep the live sheet material: $theme/$glass ($difference)", difference > minimum)
        }
    }

    @Test fun pickerThumbnailsFillSquareWideAndTallCells() {
        val images = listOf("square", "wide", "tall").map { shape -> SavedImage(id = shape,
            originalUrl = "https://material.test/red-$shape.png", thumbnailUrl = "https://material.test/red-$shape.png") }
        runBlocking { state.update { it.copy(images = images) } }
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) { EditorToolBackdropHost {
            Box(Modifier.fillMaxSize().background(Color(0xFF2564C3))) {
                CompositionLocalProvider(LocalImageLibrary provides state) {
                    EditorToolSheet("图库", {}) { ImageLibraryPicker(true) {} }
                }
            }
        } } }
        compose.waitUntil(5_000) {
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            images.all { image ->
                val bounds = compose.onNodeWithTag("library-image-${image.id}").fetchSemanticsNode().boundsInRoot
                pixels[bounds.center.x.toInt(), bounds.center.y.toInt()].red > .95f
            }
        }
        val screenshot = compose.onRoot().captureToImage()
        File(context.getExternalFilesDir(null), "library-picker-filled-thumbnails.png").outputStream().use {
            screenshot.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val pixels = screenshot.toPixelMap()
        for (image in images) {
            val bounds = compose.onNodeWithTag("library-image-${image.id}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val samples = listOf(bounds.centerLeft + Offset(3f, 0f), bounds.centerRight - Offset(3f, 0f),
                bounds.topCenter + Offset(0f, 3f), bounds.bottomCenter - Offset(0f, 3f))
            for (point in samples) {
                val color = pixels[point.x.toInt(), point.y.toInt()]
                assertTrue("${image.id} must fill its thumbnail at $point: $color", color.red > .95f && color.green < .05f && color.blue < .05f)
            }
        }
        compose.onNodeWithTag("library-image-square").performClick()
        compose.onNodeWithText("插入 1 张图片").assertIsEnabled().assertTouchHeightIsEqualTo(48.dp)
    }

    private fun pickerSurface(name: String): Color {
        val picker = compose.onNodeWithTag("editor-library-picker").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("图库").fetchSemanticsNode().boundsInRoot
        val screenshot = compose.onRoot().captureToImage()
        File(context.getExternalFilesDir(null), "library-picker-surface-$name.png").outputStream().use {
            screenshot.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val pixels = screenshot.toPixelMap()
        val expected = pixels[(picker.right - 8).toInt(), title.center.y.toInt()]
        for (point in listOf(Offset(tabs.right - 8, tabs.top + 3), Offset(picker.right - 8, picker.bottom - 20))) {
            val actual = pixels[point.x.toInt(), point.y.toInt()]
            assertEquals("$name red at $point", expected.red, actual.red, .02f)
            assertEquals("$name green at $point", expected.green, actual.green, .02f)
            assertEquals("$name blue at $point", expected.blue, actual.blue, .02f)
        }
        return expected
    }

    private fun assertSheetMaterial(changeFolder: Boolean) {
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT))
        var generation by mutableIntStateOf(0)
        compose.setContent { KudoTheme(settings) {
            key(generation) { CompositionLocalProvider(LocalImageLibrary provides state) { ImageLibraryScreen({}) {} } }
        } }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            runBlocking { state.update { library -> library.copy(images = library.images.map { it.copy(thumbnailUrl = "https://material.test/blue.png") }) } }
            compose.runOnIdle { settings = settings.copy(theme = theme, glass = glass); generation++ }
            compose.waitUntil { compose.onAllNodesWithTag("library-remove-image-40").fetchSemanticsNodes().isNotEmpty() }
            if (changeFolder) compose.onNodeWithTag("library-image-image-40").performTouchInput { longClick() }
            else compose.onNodeWithTag("library-remove-image-40").performClick()
            val sheet = compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val firstRow = if (changeFolder) compose.onNodeWithTag("collect-folder-default")
                else compose.onNodeWithText("仅从本机图库移除，不删除原帖或原图。")
            val sample = sheet.copy(bottom = firstRow
                .fetchSemanticsNode().boundsInRoot.top - 6f)
            val blue = strongestChannel(sample, blue = true)
            val previousRed = redLoads.get()
            runBlocking { state.update { library -> library.copy(images = library.images.map { it.copy(thumbnailUrl = "https://material.test/red.png") }) } }
            compose.waitUntil(5_000) { redLoads.get() > previousRed }
            val red = strongestChannel(sample, blue = false)
            println("Library ${if (changeFolder) "classification" else "removal"} material $theme/$glass blue=$blue red=$red")
            val minimum = if (glass == GlassMode.TRANSLUCENT) .025f else .08f
            assertTrue("Sheet must sample blue images: $theme/$glass ($blue)", blue > minimum)
            assertTrue("Sheet must update to red images: $theme/$glass ($red)", red > minimum)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "library-${if (changeFolder) "classification" else "removal"}-$theme-$glass.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil { compose.onAllNodesWithTag("liquid-action-sheet").fetchSemanticsNodes().isEmpty() }
        }
    }

    private fun strongestChannel(bounds: Rect, blue: Boolean): Float {
        compose.waitForIdle()
        android.os.SystemClock.sleep(300)
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        var strongest = -1f
        for (vertical in bounds.top.toInt() + 12 until bounds.bottom.toInt() - 12 step 4) {
            for (horizontal in bounds.left.toInt() + 12 until bounds.right.toInt() - 12 step 4) {
                val color = pixels[horizontal, vertical]
                strongest = maxOf(strongest, if (blue) color.blue - color.red else color.red - color.blue)
            }
        }
        return strongest
    }
}
