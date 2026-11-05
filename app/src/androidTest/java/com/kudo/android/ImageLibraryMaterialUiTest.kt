package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import coil3.*
import coil3.decode.DataSource
import coil3.fetch.*
import coil3.request.Options
import com.kudo.android.data.*
import com.kudo.android.ui.library.*
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
                        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
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
