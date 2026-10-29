package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
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

    @Test fun collectionSheetUsesCompactRowsWithoutSecondaryInstructions() {
        seed()
        screen { Column { RichContent(html, {}, {}, imageSource = first.source) } }
        compose.onNodeWithTag("article-image-article-0").performTouchInput { longClick() }
        compose.onNodeWithText("收藏图片").performClick()
        compose.waitUntil { compose.onAllNodesWithTag("collect-folder-default").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("选择收藏目录").assertDoesNotExist()
        compose.onNodeWithTag("collect-folder-default").assertHeightIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("collect-new-folder").assertHeightIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 2_000)
        capture("image-library-compact-collect.png")
    }

    @Test fun libraryFiltersDirectoriesAndOpensExactSourceFloor() {
        seed()
        var source: ImageSource? = null
        screen { ImageLibraryScreen({}) { source = it } }
        loaded()
        compose.onNodeWithText("全部").assertIsSelected()
        compose.onNodeWithText("来源主题").assertDoesNotExist()
        compose.onNodeWithText("表情").performClick()
        compose.onNodeWithTag("library-item-first").assertDoesNotExist()
        compose.onNodeWithTag("library-item-second").assertIsDisplayed()
        compose.onNodeWithTag("library-source-second").assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals("打开来源主题，第 8 楼").performClick()
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
        compose.onNodeWithText("删除目录后，里面的图片会移入默认收藏。").assertIsDisplayed()
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
        compose.onNodeWithText("仅从本机图库移除，不删除原帖或原图。").assertIsDisplayed()
        assertEquals(2, runBlocking { store.load(101).images.size })
        compose.onNodeWithTag("library-confirm-remove").performClick()
        compose.waitUntil { runBlocking { store.load(101).images.size == 1 } }
        assertEquals("second", runBlocking { store.load(101).images.single().id })
    }

    @Test fun standaloneLibraryRemovalSamplesThePageRatherThanAnOpaqueFallback() {
        seed()
        compose.setContent { KudoTheme(DisplaySettings()) {
            ImageLibraryEnvironment(store, 101, true) {
                Box(Modifier.fillMaxSize().background(Color.Blue)) { ImageLibraryScreen({}) {} }
            }
        } }
        loaded()
        compose.onNodeWithTag("library-remove-first").performClick()
        compose.onNodeWithTag("editor-tool-backdrop-source", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("仅从本机图库移除，不删除原帖或原图。").assertIsDisplayed()
    }

    @Test fun newTopicInsertsMultipleOriginalLinksAtSelectionWithoutUploadOrPublish() {
        seed()
        var draft by mutableStateOf(Draft(title = "标题", raw = "前原文后", categoryId = 1))
        screen { EditorForm(draft, emptyList(), { draft = it }, { fail("Unexpected publish") }, { fail("Unexpected upload") }, {}) }
        compose.onNodeWithTag("editor-body").performTextInputSelection(TextRange(1, 3))
        openTool()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library-image-first").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("使用原图链接，不重新上传").assertDoesNotExist()
        compose.onNodeWithText("插入图片").assertIsNotEnabled()
        compose.onNodeWithTag("library-image-first").performClick()
        compose.onNodeWithText("表情").performClick()
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
        compose.onNodeWithTag("editor-library-insert").assertTouchHeightIsEqualTo(48.dp)
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

    @Test fun emptyLibraryUsesOneStatusWithoutSecondaryInstructions() {
        screen { ImageLibraryScreen({}) {} }
        compose.waitUntil { compose.onAllNodesWithText("暂无收藏图片").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("长按帖子里的图片，即可收藏到目录。").assertDoesNotExist()
        capture("image-library-compact-empty.png")
    }

    @Test fun emptyLibraryCanCollapseItsTitleSwitchFoldersAndExpandAgain() {
        screen { ImageLibraryScreen({}) {} }
        compose.waitUntil { compose.onAllNodesWithText("暂无收藏图片").fetchSemanticsNodes().isNotEmpty() }
        val original = compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot.top
        val pager = compose.onNodeWithTag("library-pager")
        pager.performTouchInput { swipeUp(durationMillis = 700) }
        compose.onNodeWithText("图库管理").assertIsNotDisplayed()
        val pinned = compose.onNodeWithTag("library-folders").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue(pinned < original - 40f)
        pager.performTouchInput { swipeUp(durationMillis = 700) }
        assertEquals(pinned, compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot.top, 1f)
        pager.performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithText("默认收藏").assertIsSelected()
        compose.onNodeWithText("暂无收藏图片").assertIsDisplayed()
        capture("library-empty-tabs-pinned.png")
        pager.performTouchInput { swipe(Offset(center.x, height * .55f), Offset(center.x, height * .85f), durationMillis = 700) }
        compose.onNodeWithText("图库管理").assertIsDisplayed()
        assertEquals(original, compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithContentDescription("管理图库目录").performClick()
        compose.onNodeWithText("新建目录").assertIsDisplayed()
    }

    @Test fun shortLibraryPagesKeepPinnedTabsInADarkNarrowLargeTextWindow() {
        seed()
        screen(dark = true, large = true) { Box(Modifier.width(280.dp)) { ImageLibraryScreen({}) {} } }
        loaded()
        compose.onNodeWithText("默认收藏").performClick()
        val original = compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("library-images").performTouchInput { swipeUp(durationMillis = 700) }
        compose.onNodeWithText("图库管理").assertIsNotDisplayed()
        val pinned = compose.onNodeWithTag("library-folders").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue(pinned < original - 40f)
        compose.onNodeWithTag("library-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.onNodeWithText("表情").assertIsSelected()
        compose.onNodeWithTag("library-item-second").assertIsDisplayed()
        assertEquals(pinned, compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot.top, 1f)
        capture("library-short-dark-large-pinned.png")
        compose.onNodeWithTag("library-images").performTouchInput {
            swipe(Offset(center.x, height * .55f), Offset(center.x, height * .85f), durationMillis = 700)
        }
        compose.onNodeWithText("图库管理").assertIsDisplayed()
    }

    @Test fun swipingDirectoryPagesUpdatesTabsAndPreservesSelection() {
        seed()
        screen { Column { ImageLibraryPicker(true) {} } }
        loaded()
        compose.onNodeWithTag("library-image-first").performClick()
        compose.onNodeWithTag("library-pager").performTouchInput { swipeLeft(durationMillis = 500) }
        compose.onNodeWithText("默认收藏").assertIsSelected()
        compose.onNodeWithTag("library-item-second").assertDoesNotExist()
        compose.onNodeWithTag("library-pager").performTouchInput { swipeLeft(durationMillis = 500) }
        compose.onNodeWithText("表情").assertIsSelected()
        compose.onNodeWithTag("library-image-second").performClick()
        compose.onNodeWithText("插入 2 张图片").assertIsEnabled()
        compose.onNodeWithTag("library-pager").performTouchInput { swipeRight(durationMillis = 500) }
        compose.onNodeWithText("默认收藏").assertIsSelected()
        compose.onNodeWithTag("library-image-first").assertIsSelected()
        capture("library-home-tabs-picker.png")
    }

    @Test fun libraryTitleCollapsesWhileTabsStayPinnedAndReverseScrollExpandsIt() {
        seed()
        runBlocking { store.update(101) { library ->
            (1..40).fold(library) { updated, index -> updated.save(first.copy(id = "extra-$index", originalUrl = "https://library.test/$index.png")) }
        } }
        screen { ImageLibraryScreen({}) {} }
        compose.waitUntil { compose.onAllNodesWithTag("library-images").fetchSemanticsNodes().isNotEmpty() }
        val originalTop = compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("library-images").performTouchInput { swipeUp(durationMillis = 700) }
        compose.onNodeWithText("图库管理").assertIsNotDisplayed()
        val pinnedTop = compose.onNodeWithTag("library-folders").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue(pinnedTop < originalTop - 40f)
        compose.onNodeWithTag("library-images").performTouchInput { swipeUp(durationMillis = 700) }
        assertEquals(pinnedTop, compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot.top, 1f)
        capture("library-tabs-pinned.png")
        val tabs = compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("library-images")
        val viewport = grid.fetchSemanticsNode().boundsInRoot
        grid.performTouchInput {
            swipe(Offset(center.x, maxOf(tabs.bottom - viewport.top + 16f, height * .2f)),
                Offset(center.x, height * .9f), durationMillis = 700)
        }
        compose.onNodeWithText("图库管理").assertIsDisplayed()
        assertEquals(originalTop, compose.onNodeWithTag("library-folders").fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun removingTheCurrentFolderReturnsToAllWithoutLosingImages() {
        seed()
        screen { ImageLibraryScreen({}) {} }
        loaded()
        compose.onNodeWithText("表情").performClick()
        compose.onNodeWithContentDescription("管理图库目录").performClick()
        compose.onNodeWithContentDescription("删除表情").performClick()
        compose.onNodeWithText("删除目录", substring = false).performClick()
        compose.waitUntil { runBlocking { store.load(101).folders.size == 1 } }
        compose.onNodeWithText("全部").assertIsSelected()
        compose.onNodeWithTag("library-item-first").assertIsDisplayed()
        compose.onNodeWithTag("library-item-second").assertIsDisplayed()
    }

    @Test fun directoryPagesKeepIndependentReadingPositionsInADarkNarrowWindow() {
        seed()
        runBlocking { store.update(101) { library ->
            (1..40).fold(library) { updated, index ->
                updated.save(first.copy(id = "default-$index", originalUrl = "https://library.test/default-$index.png"))
                    .save(second.copy(id = "emoji-$index", originalUrl = "https://library.test/emoji-$index.png"))
            }
        } }
        screen(dark = true, large = true) { Box(Modifier.width(280.dp).fillMaxHeight()) { ImageLibraryScreen({}) {} } }
        compose.waitUntil { compose.onAllNodesWithTag("library-images").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("默认收藏").performScrollTo().performClick()
        compose.onNodeWithTag("library-images").performScrollToIndex(12)
        val before = compose.onNodeWithTag("library-images").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithText("表情").performScrollTo().performClick()
        compose.onNodeWithTag("library-images").performScrollToIndex(4)
        compose.onNodeWithTag("library-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithText("默认收藏").assertIsSelected()
        val restored = compose.onNodeWithTag("library-images").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, restored, 1f)
        capture("library-directory-position-dark-narrow.png")
    }

    @Test fun switchingAccountsClosesLibraryManagementAndResetsItsPages() {
        seed()
        var account by mutableStateOf<Int?>(101)
        compose.setContent { KudoTheme(DisplaySettings()) {
            ImageLibraryEnvironment(store, account, true) { ImageLibraryScreen({}) {} }
        } }
        loaded()
        compose.onNodeWithText("表情").performClick()
        compose.onNodeWithTag("library-remove-second").performClick()
        compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
        compose.runOnIdle { account = 202 }
        compose.waitUntil { compose.onAllNodesWithText("暂无收藏图片").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithText("全部").assertIsSelected()
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
        compose.onNodeWithText("按目录管理收藏图片").assertDoesNotExist()
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
