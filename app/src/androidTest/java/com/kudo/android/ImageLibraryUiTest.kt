package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.*
import coil3.decode.DataSource
import coil3.fetch.*
import coil3.request.Options
import com.kudo.android.data.*
import com.kudo.android.media.*
import com.kudo.android.ui.account.ProfileScreen
import com.kudo.android.ui.editor.*
import com.kudo.android.ui.library.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@OptIn(coil3.annotation.DelicateCoilApi::class)
class ImageLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "library_ui_test_${UUID.randomUUID()}"
    private val fixture = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(preferenceName, mode)
    }
    private val store = ImageLibraryStore(fixture)
    private lateinit var previousLoader: ImageLoader
    private lateinit var loader: ImageLoader
    private lateinit var services: ImageExportServices
    private val first = SavedImage(id = "first", originalUrl = "https://library.test/first.png", alt = "第一张", source = ImageSource(42, 8, "来源主题"))
    private val second = first.copy(id = "second", folderId = "emoji", originalUrl = "https://library.test/second.png", thumbnailUrl = "https://library.test/second.png", alt = "第二张")
    private val html = "<p><img src='${first.originalUrl}' alt='第一张'><img src='${second.originalUrl}' alt='第二张'></p>"

    @Before fun setup() {
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff46c598.toInt()) }
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        previousLoader = SingletonImageLoader.get(base)
        loader = ImageLoader.Builder(base).memoryCache(null).diskCache(null).components {
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? = object : Fetcher {
                    override suspend fun fetch(): FetchResult = ImageFetchResult(bitmap.asImage(), false, DataSource.MEMORY)
                }
            })
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .header("Content-Length", bytes.size.toString()).body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val downloader = ImageDownloader(http, File(base.cacheDir, preferenceName))
        services = ImageExportServices(downloader, ImageSaver(downloader, ImageStore { fail("Unexpected download"); error("Unexpected download") })) { fail("Unexpected share") }
    }

    @After fun cleanup() {
        SingletonImageLoader.setUnsafe(previousLoader)
        loader.shutdown()
        base.deleteSharedPreferences(preferenceName)
    }

    private fun seed(account: Int? = 101) = runBlocking {
        store.update(account) { it.addFolder("表情", "emoji").save(first).save(second) }
    }

    private fun screen(account: Int? = 101, dark: Boolean = false, large: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            KudoTheme(DisplaySettings(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.3f else 1f), LocalImageExportServices provides services) {
                    EditorToolBackdropHost {
                        ImageLibraryEnvironment(store, account, true) {
                            ImageGalleryHost({ emptyList() }, {}, account) { content() }
                        }
                    }
                }
            }
        }
    }

    private fun loaded() = compose.waitUntil { compose.onAllNodesWithTag("library-item-first").fetchSemanticsNodes().isNotEmpty() }
    private fun openTool() = compose.onNodeWithContentDescription("图库").performScrollTo().performClick()
    private fun capture(name: String) {
        val file = File(base.getExternalFilesDir(null), name)
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun inlineLongPressOffersFourActionsAndNewFolderKeepsThePostSource() {
        screen { Column { RichContent(html, {}, {}, imageSource = first.source) } }
        compose.onNodeWithTag("article-image-article-1").performTouchInput { longClick() }
        compose.onNodeWithText("分享图片").assertIsDisplayed()
        compose.onNodeWithText("下载图片", substring = true).assertIsDisplayed()
        compose.onNodeWithText("下载全部", substring = true).assertIsDisplayed()
        compose.onNodeWithText("收藏图片").performClick()
        compose.onNodeWithTag("collect-new-folder").performClick()
        compose.onNodeWithTag("collect-folder-name").performTextInput("常用表情")
        compose.onNodeWithText("新建并收藏").performScrollTo().performClick()
        compose.waitUntil { runBlocking { store.load(101).images.size == 1 } }
        val saved = runBlocking { store.load(101) }
        assertEquals("常用表情", saved.folders.last().name)
        assertEquals(second.originalUrl, saved.images.single().originalUrl)
        assertEquals(first.source, saved.images.single().source)
    }

    @Test fun fullScreenCollectsTheCurrentlyDisplayedImageToTheDefaultFolder() {
        screen { Column { RichContent(html, {}, {}, imageSource = first.source) } }
        compose.onNodeWithTag("article-image-article-1").performClick()
        compose.onNodeWithTag("gallery-page-1").performTouchInput { longClick() }
        compose.onNodeWithText("收藏图片").performClick()
        compose.onNodeWithTag("collect-folder-default").performClick()
        compose.waitUntil { runBlocking { store.load(101).images.size == 1 } }
        assertEquals(second.originalUrl, runBlocking { store.load(101) }.images.single().originalUrl)
        assertEquals(first.source, runBlocking { store.load(101) }.images.single().source)
    }

    @Test fun cancelingCollectionDoesNotWriteAnything() {
        screen { Column { RichContent(html, {}, {}) } }
        compose.onNodeWithTag("article-image-article-0").performTouchInput { longClick() }
        compose.onNodeWithText("收藏图片").performClick()
        compose.onNodeWithTag("collect-folder-default").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { compose.onAllNodesWithTag("collect-folder-default").fetchSemanticsNodes().isEmpty() }
        assertEquals(ImageLibrary(), runBlocking { store.load(101) })
    }

    @Test fun libraryFiltersDirectoriesAndOpensExactSourceFloor() {
        seed()
        var source: ImageSource? = null
        screen { ImageLibraryScreen({}) { source = it } }
        loaded()
        compose.onNodeWithTag("library-folder-emoji").performClick()
        compose.onNodeWithTag("library-item-first").assertDoesNotExist()
        compose.onNodeWithTag("library-item-second").assertIsDisplayed()
        compose.onNodeWithTag("library-source-second").performClick()
        assertEquals(ImageSource(42, 8, "来源主题"), source)
        compose.onNodeWithTag("library-image-second").performClick()
        compose.onNodeWithTag("gallery-page-0").assertIsDisplayed()
    }

    @Test fun folderRenameAndDeleteMoveImagesRatherThanDeletingThem() {
        seed()
        screen { ImageLibraryScreen({}) {} }
        loaded()
        compose.onNodeWithContentDescription("管理图库目录").performClick()
        compose.onNodeWithContentDescription("重命名表情").performClick()
        compose.onNodeWithTag("library-folder-name").performTextReplacement("新表情")
        compose.onNodeWithText("保存目录").performClick()
        compose.waitUntil { runBlocking { store.load(101).folders.last().name == "新表情" } }
        compose.onNodeWithContentDescription("管理图库目录").performClick()
        compose.onNodeWithContentDescription("删除新表情").performClick()
        compose.onNodeWithText("删除目录", substring = false).performClick()
        compose.waitUntil { runBlocking { store.load(101).folders.size == 1 } }
        assertEquals(2, runBlocking { store.load(101).images.size })
        assertTrue(runBlocking { store.load(101) }.images.all { it.folderId == DEFAULT_IMAGE_FOLDER })
    }

    @Test fun removingImageRequiresConfirmationAndDoesNotRemoveOtherImages() {
        seed()
        screen { ImageLibraryScreen({}) {} }
        loaded()
        compose.onNodeWithTag("library-remove-first").performClick()
        assertEquals(2, runBlocking { store.load(101).images.size })
        compose.onNodeWithTag("library-confirm-remove").performClick()
        compose.waitUntil { runBlocking { store.load(101).images.size == 1 } }
        assertEquals("second", runBlocking { store.load(101).images.single().id })
    }

    @Test fun newTopicInsertsMultipleOriginalLinksAtSelectionWithoutUploadOrPublish() {
        seed()
        var draft by mutableStateOf(Draft(title = "标题", raw = "前原文后", categoryId = 1))
        screen { EditorForm(draft, emptyList(), { draft = it }, { fail("Unexpected publish") }, { fail("Unexpected upload") }, {}) }
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(1, 3))
        openTool()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library-image-first").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-image-first").performClick()
        compose.onNodeWithTag("library-folder-emoji").performClick()
        compose.onNodeWithTag("library-image-second").performClick()
        compose.onNodeWithText("插入 2 张图片").performScrollTo().performClick()
        compose.waitUntil { draft.raw.contains("![第二张]") }
        assertEquals("前原文\n\n![第一张](https://library.test/first.png)\n\n![第二张](https://library.test/second.png)\n\n后", draft.raw)
    }

    @Test fun replyPickerUsesFullWindowWithDarkLargeTextAndKeepsDraftOnCancel() {
        seed()
        var draft by mutableStateOf(Draft(raw = "保留草稿"))
        screen(dark = true, large = true) {
            Box(Modifier.width(280.dp).height(300.dp)) { EditorForm(draft, emptyList(), { draft = it }, { fail("Unexpected publish") }, {}, {}, reply = true) }
        }
        compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.waitForIdle()
        openTool()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library-image-first").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-image-first").performClick()
        compose.onNodeWithText("插入 1 张图片").performScrollTo().assertIsDisplayed()
        capture("image-library-picker-dark-large.png")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { compose.onAllNodesWithTag("editor-library-picker").fetchSemanticsNodes().isEmpty() }
        assertEquals("保留草稿", draft.raw)
    }

    @Test fun accountSwitchHidesPreviousLibraryAndClosesTheOldPicker() {
        seed()
        var account by mutableStateOf<Int?>(101)
        compose.setContent { KudoTheme(DisplaySettings()) { EditorToolBackdropHost {
            ImageLibraryEnvironment(store, account, true) {
                EditorForm(Draft(title = "标题", raw = "草稿", categoryId = 1), emptyList(), {}, {}, {}, {})
            }
        } } }
        openTool()
        compose.waitUntil { compose.onAllNodesWithTag("library-image-first").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { account = 202 }
        compose.waitUntil { compose.onAllNodesWithTag("editor-library-picker").fetchSemanticsNodes().isEmpty() }
        openTool()
        compose.onNodeWithText("暂无收藏图片").assertIsDisplayed()
        assertEquals(2, runBlocking { store.load(101).images.size })
        assertTrue(runBlocking { store.load(202).images.isEmpty() })
    }

    @Test fun replyInsertsImageAndPreservesTheReplyDraftWithoutPublishing() {
        seed()
        var draft by mutableStateOf(Draft(raw = "回复内容"))
        screen { Box(Modifier.fillMaxWidth().height(300.dp)) {
            EditorForm(draft, emptyList(), { draft = it }, { fail("Unexpected publish") }, { fail("Unexpected upload") }, {}, reply = true)
        } }
        openTool()
        compose.waitUntil { compose.onAllNodesWithTag("library-image-first").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-image-first").performClick()
        compose.onNodeWithText("插入 1 张图片").performScrollTo().performClick()
        compose.waitUntil { draft.raw.contains("![第一张]") }
        assertEquals("回复内容\n\n![第一张](https://library.test/first.png)\n", draft.raw)
    }

    @Test fun darkLargeLibraryRemainsReadableWithSourceAndRemoveActions() {
        seed()
        screen(dark = true, large = true) { Box(Modifier.width(280.dp).fillMaxHeight()) { ImageLibraryScreen({}) {} } }
        loaded()
        compose.onNodeWithTag("library-source-first").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("library-remove-first").assertIsDisplayed()
        capture("image-library-management-dark-large.png")
    }

    @Test fun corruptLibraryShowsRetryAndNeverOffersAnEmptyWritableLibrary() {
        assertTrue(base.getSharedPreferences(preferenceName, 0).edit().putString("user_101", "broken").commit())
        screen { ImageLibraryScreen({}) {} }
        compose.waitUntil { compose.onAllNodesWithText("图库读取失败，请重试；原有数据不会被覆盖").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("暂无收藏图片").assertDoesNotExist()
        assertEquals("broken", base.getSharedPreferences(preferenceName, 0).getString("user_101", null))
    }

    @Test fun footnoteImagesCanBeCollectedWithTheirOriginalPostSource() {
        val footnote = "<p>正文<sup class='footnote-ref'><a href='#fn1'>[1]</a></sup></p><ol class='footnotes-list'><li id='fn1'><img src='${first.originalUrl}'></li></ol>"
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalImageExportServices provides services) { EditorToolBackdropHost { ArticleFootnoteHost {
                ImageLibraryEnvironment(store, 101, true) { Column { RichContent(footnote, {}, {}, imageSource = first.source) } }
            } } }
        } }
        val text = compose.onNodeWithText("正文[1]")
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        text.performTouchInput { click(layouts.single().getBoundingBox(3).center) }
        compose.onNodeWithTag("article-image-footnote-fn1-0").performTouchInput { longClick() }
        compose.onNodeWithText("收藏图片").assertIsDisplayed().performClick()
        compose.onNodeWithTag("collect-folder-default").performClick()
        compose.waitUntil(5_000) { runBlocking { store.load(101).images.isNotEmpty() } }
        assertEquals(first.source, runBlocking { store.load(101) }.images.single().source)
    }

    @Test fun guestProfilePlacesGalleryBetweenSettingsAndDownloads() {
        var opened = false
        screen(account = null) { ProfileScreen(null, {}, {}, {}, {}, imageLibrary = { opened = true }) }
        val settings = compose.onNodeWithText("设置").fetchSemanticsNode().boundsInRoot.top
        val gallery = compose.onNodeWithText("图库管理").fetchSemanticsNode().boundsInRoot.top
        val downloads = compose.onNodeWithText("下载管理").fetchSemanticsNode().boundsInRoot.top
        assertTrue(settings < gallery && gallery < downloads)
        compose.onNodeWithText("图库管理").performClick()
        assertTrue(opened)
    }

    @Test fun previewImageCollectionUsesThePreviewWindowRatherThanTheCoveredAppWindow() {
        fun windowRoot(node: SemanticsNode): Int {
            var current = node
            while (current.parent != null) current = requireNotNull(current.parent)
            return current.id
        }
        screen { EditorPreviewDialog("![第一张](${first.originalUrl})") {} }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("article-image-editor-preview-0").fetchSemanticsNodes().isNotEmpty() }
        val previewWindow = windowRoot(compose.onNodeWithTag("markdown-preview", useUnmergedTree = true).fetchSemanticsNode())
        compose.onNodeWithTag("article-image-editor-preview-0").performTouchInput { longClick() }
        compose.onNodeWithText("收藏图片").performClick()
        assertEquals(previewWindow, windowRoot(compose.onNodeWithTag("collect-folder-default", useUnmergedTree = true).fetchSemanticsNode()))
        compose.onNodeWithTag("collect-folder-default").performClick()
        compose.waitUntil(5_000) { runBlocking { store.load(101).images.isNotEmpty() } }
        assertEquals(first.originalUrl, runBlocking { store.load(101) }.images.single().originalUrl)
        assertNull(runBlocking { store.load(101) }.images.single().source)
        compose.onNodeWithTag("markdown-preview").assertIsDisplayed()
    }
}
