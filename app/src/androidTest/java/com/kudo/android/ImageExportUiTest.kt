package com.kudo.android

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import com.kudo.android.data.*
import com.kudo.android.media.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class ImageExportUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun missingCurrentCapacityUpdatesFromHeadWithoutDownloadingOrProbingTheAlbum() {
        val requests = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("HEAD", chain.request().method)
            assertEquals("/original.webp", chain.request().url.encodedPath)
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Length", "2401362").header("Content-Type", "image/webp")
                .body(byteArrayOf().toResponseBody("image/webp".toMediaType())).build()
        }.build()
        val downloader = ImageDownloader(http, context.cacheDir)
        val services = ImageExportServices(downloader, ImageSaver(downloader, ImageStore { fail("Unexpected save") })) { fail("Unexpected share") }
        val current = ArticleBlock.Image("https://capacity.test/preview.webp", "", originalUrl = "https://capacity.test/original.webp")
        val other = current.copy(originalUrl = "https://capacity.test/other.webp")
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalImageExportServices provides services) {
                ImageActions(ImageActionTarget(current, listOf(current, other)), {}, {})
            }
        } }
        compose.waitUntil { compose.onAllNodesWithText("下载图片（2.3 MB）").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("下载图片（2.3 MB）").assertIsDisplayed()
        compose.onNodeWithText("下载全部").assertIsDisplayed()
        compose.onNodeWithText("容量未知", substring = true).assertDoesNotExist()
        captureMenu("image-capacity-from-head.png")
        compose.runOnIdle { assertEquals(1, requests.get()) }
    }

    @Test fun productionMenuReadsTheOriginalCoilCacheInsteadOfThePreviewSize() {
        val cache = SingletonImageLoader.get(context).diskCache!!
        val original = "https://capacity-cache.test/${System.nanoTime()}.webp"
        val preview = "$original-preview"
        fun put(url: String, bytes: Int) {
            val editor = cache.openEditor(url)!!
            try {
                cache.fileSystem.write(editor.metadata) { writeUtf8("fixture") }
                cache.fileSystem.write(editor.data) { write(ByteArray(bytes)) }
                editor.commit()
            } catch (e: Exception) { editor.abort(); throw e }
        }
        put(original, 4096)
        put(preview, 1024)
        try {
            val image = ArticleBlock.Image(preview, "", originalUrl = original)
            compose.setContent { KudoTheme(DisplaySettings()) {
                ImageActions(ImageActionTarget(image, listOf(image)), {}, {})
            } }
            compose.waitUntil { compose.onAllNodesWithText("下载图片（4 KB）").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("下载图片（4 KB）").assertIsDisplayed()
            compose.onNodeWithText("下载图片（1 KB）").assertDoesNotExist()
        } finally { cache.remove(original); cache.remove(preview) }
    }

    @Test fun missingCapacityOmitsParenthesesAndDoesNotHideKnownCurrentCapacity() {
        val unknown = ArticleBlock.Image("https://image.test/animation.webp", "")
        var target by mutableStateOf(ImageActionTarget(unknown, listOf(unknown)))
        compose.setContent { KudoTheme(DisplaySettings()) { ImageActionMenu(target, {}, {}) } }
        compose.onNodeWithText("下载图片").assertIsDisplayed()
        compose.onNodeWithText("容量未知", substring = true).assertDoesNotExist()
        captureMenu("image-capacity-omitted.png")
        compose.runOnIdle {
            val known = unknown.copy(originalUrl = "https://image.test/known.png", sizeLabel = "512 KB")
            target = ImageActionTarget(known, listOf(known, unknown))
        }
        compose.onNodeWithText("下载图片（512 KB）").assertIsDisplayed()
        compose.onNodeWithText("下载全部").assertIsDisplayed()
        compose.onNodeWithText("容量未知", substring = true).assertDoesNotExist()
    }

    @Test fun singleImageHidesBatchAndDarkLargeTextMenuRemainsReadable() {
        var multiple by mutableStateOf(false)
        val current = ArticleBlock.Image("https://image.test/1.png", "", sizeLabel = "512 KB")
        val second = current.copy(url = "https://image.test/2.png", originalUrl = "https://image.test/2.png", sizeLabel = "1 MB")
        var selected: ImageAction? = null
        var dismissed = false
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            ImageActionMenu(ImageActionTarget(current, if (multiple) listOf(current, second) else listOf(current)),
                { dismissed = true }, { selected = it })
        } }
        compose.onNodeWithText("下载图片（512 KB）").assertIsDisplayed()
        compose.onNodeWithText("下载全部", substring = true).assertDoesNotExist()
        compose.onNodeWithText("分享图片").performClick()
        compose.runOnIdle { assertEquals(ImageAction.SHARE, selected); multiple = true }
        compose.onNodeWithText("下载全部（约 1.5 MB）").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ImageAction.SAVE_ALL, selected); assertFalse(dismissed) }
        val bitmap = compose.onNodeWithTag("image-action-menu").captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        File(directory, "image-menu-dark-large.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun galleryAndSystemShareKeepAnimationBytesAndOnlyExposeTheImageCache() {
        if (Build.VERSION.SDK_INT < 29) return // Scoped-storage integration; legacy permission requires a separate device.
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("avatars/three-frame.gif").use { it.readBytes() }
        val directory = File(context.cacheDir, "image_exports").apply { mkdirs() }
        val file = File.createTempFile("export-test-", ".gif", directory).apply { writeBytes(bytes) }
        val name = "export-test-${System.nanoTime()}.gif"
        val image = OriginalImageFile(file, "image/gif", name)
        var saved: Uri? = null
        try {
            val intent = imageShareIntent(context, image)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("image/gif", intent.type)
            @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            saved = GalleryImageStore(context).insert(image)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(saved)!!.use { it.readBytes() })
            context.contentResolver.query(saved, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.RELATIVE_PATH), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)); assertTrue(cursor.getString(1).startsWith("Pictures/酷DO"))
            }
            val outside = File.createTempFile("private-test-", ".txt", context.cacheDir)
            try {
                try { FileProvider.getUriForFile(context, "${context.packageName}.image-files", outside); fail("Private cache exposed") }
                catch (_: IllegalArgumentException) { }
            } finally { outside.delete() }
        } finally { saved?.let { context.contentResolver.delete(it, null, null) }; file.delete() }
    }

    @Test fun failedGalleryWriteRemovesThePendingEntry() {
        if (Build.VERSION.SDK_INT < 29) return
        val name = "failed-export-${System.nanoTime()}.gif"
        val missing = File(context.cacheDir, "missing-${System.nanoTime()}")
        try { GalleryImageStore(context).insert(OriginalImageFile(missing, "image/gif", name)); fail("Missing file accepted") }
        catch (_: IOException) { }
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use { assertEquals(0, it.count) }
    }

    private fun captureMenu(name: String) {
        val directory = File(context.getExternalFilesDir(null), "ui-tests").apply { mkdirs() }
        File(directory, name).outputStream().use {
            compose.onNodeWithTag("image-action-menu").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
