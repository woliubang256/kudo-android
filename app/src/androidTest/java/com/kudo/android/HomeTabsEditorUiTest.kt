package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.feed.*
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.*
import org.junit.Assert.*
import java.io.File

class HomeTabsEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var model: HomeTabsEditorViewModel? = null
    private var saved: List<HomeTab>? = null
    private var backCount = 0
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = if (request.url.encodedPath == "/site.json")
            """{"categories":[{"id":4,"name":"开发调优","slug":"dev"},{"id":20,"name":"开发子分类","slug":"child","parent_category_id":4}]}"""
        else """{"results":[{"id":444,"text":"公告","slug":"444-tag"}]}"""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
    }.build()

    @Before fun configure() { compose.runOnUiThread { compose.activity.enableEdgeToEdge() } }
    @After fun dispose() {
        compose.runOnIdle { model?.viewModelScope?.cancel() }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun show(tabs: List<HomeTab> = defaultHomeTabs(false), narrow: Boolean = false) {
        val repository = CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) {}
        }, http = http))
        compose.runOnUiThread { model = HomeTabsEditorViewModel(repository, null, tabs) { saved = it } }
        compose.setContent { KudoTheme(DisplaySettings(theme = if (narrow) ThemeMode.DARK else ThemeMode.LIGHT,
            fontScale = if (narrow) 1.3f else 1f)) {
            Box(if (narrow) Modifier.width(280.dp).height(600.dp) else Modifier.fillMaxSize()) {
                val state by model!!.state.collectAsState()
                HomeTabsEditorScreen(state, model!!::move, model!!::add, model!!::remove, model!!::restore,
                    model!!::query, model!!::source, model!!::retry, model!!::save, { backCount++ }, {})
            }
        } }
        compose.waitUntil(5000) { !model!!.state.value.categoriesLoading }
    }

    @Test fun removingAddingBackAndRestoringDefaultsStayDraftsUntilSave() {
        show(listOf(HomeTab.filter(FeedFilter.LATEST), HomeTab.filter(FeedFilter.HOT)))
        compose.onNodeWithTag("home-tabs-remove-latest").performClick()
        compose.onNodeWithTag("home-tabs-remove-hot").assertIsNotEnabled()
        compose.onNodeWithTag("home-tabs-add-latest").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("hot", "latest"), model!!.state.value.tabs.map { it.key })
            assertNull(saved)
        }
        compose.onNodeWithTag("home-tabs-restore").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(defaultHomeTabs(false), model!!.state.value.tabs); assertNull(saved) }
        compose.onNodeWithTag("home-tabs-save").performClick()
        compose.runOnIdle { assertEquals(defaultHomeTabs(false), saved); assertFalse(model!!.state.value.dirty) }
        capture("home-tabs-editor-light.png")
    }

    @Test fun rootCategoriesAndSearchedTagsCanBeAddedWithoutDuplicates() {
        show(listOf(HomeTab.filter(FeedFilter.LATEST)))
        compose.onNodeWithTag("home-tabs-source-CATEGORY").performScrollTo().performClick()
        compose.onNodeWithTag("home-tabs-add-category:4").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("home-tabs-add-category:20").assertDoesNotExist()
        compose.onNodeWithTag("home-tabs-source-TAG").performScrollTo().performClick()
        compose.onNodeWithText("搜索标签").assertIsDisplayed()
        compose.onNodeWithText("输入关键词搜索标签").assertDoesNotExist()
        compose.onNodeWithTag("home-tabs-query").performScrollTo().performTextReplacement("公告")
        compose.onNodeWithTag("home-tabs-query").performImeAction()
        compose.waitUntil(5000) { !model!!.state.value.tagsLoading && model!!.state.value.tags.isNotEmpty() }
        compose.onNodeWithTag("home-tabs-add-tag:444").performScrollTo().performClick().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("latest", "category:4", "tag:444"), model!!.state.value.tabs.map { it.key })
            assertNull(saved)
        }
        compose.onNodeWithTag("home-tabs-save").performClick()
        compose.runOnIdle { assertEquals(model!!.state.value.tabs, saved) }
        capture("home-tabs-editor-added-tag.png")
    }

    @Test fun backCanKeepEditingOrDiscardWithoutSavingInADarkNarrowWindow() {
        show(listOf(HomeTab.filter(FeedFilter.LATEST)), narrow = true)
        compose.onNodeWithTag("home-tabs-restore").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        capture("home-tabs-editor-discard-dark-narrow.png")
        compose.onNodeWithTag("home-tabs-keep-editing").performClick()
        compose.runOnIdle { assertEquals(0, backCount); assertNull(saved) }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("home-tabs-discard").performClick()
        compose.runOnIdle { assertEquals(1, backCount); assertNull(saved) }
    }

    @Test fun compactEditorOmitsRepeatedLabelsAndKeepsTouchTargets() {
        show(listOf(HomeTab.filter(FeedFilter.LATEST)))
        listOf("定制访客首页", "长按手柄拖动排序，修改只在保存后生效。", "内置筛选", "分类目录", "一级分类", "仅显示一级分类").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        val row = compose.onNodeWithTag("home-tabs-selected-latest").getUnclippedBoundsInRoot()
        assertTrue("Selected rows stay compact", row.bottom - row.top <= 57.dp)
        compose.onNodeWithTag("home-tabs-drag-latest").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("home-tabs-remove-latest").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("home-tabs-add-hot").performScrollTo().assertTouchHeightIsEqualTo(48.dp)
        capture("home-tabs-editor-compact-light.png")
    }

    @Test fun compactEditorSupportsCategorySearchInADarkNarrowWindow() {
        show(listOf(HomeTab.filter(FeedFilter.LATEST)), narrow = true)
        compose.onNodeWithTag("home-tabs-source-CATEGORY").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("仅显示一级分类").assertDoesNotExist()
        compose.onNodeWithTag("home-tabs-query").performScrollTo().performTextReplacement("开发")
        compose.onNodeWithTag("home-tabs-query").performImeAction()
        compose.onNodeWithTag("home-tabs-add-category:4").performScrollTo().assertIsDisplayed()
        capture("home-tabs-editor-compact-dark-narrow.png")
    }

    @Test fun longPressDragChangesOrderAndAccessibilityCanMoveItBack() {
        show()
        val first = compose.onNodeWithTag("home-tabs-drag-latest").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("home-tabs-drag-hot").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("home-tabs-drag-hot").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center + Offset(0f, first.center.y - second.center.y - 30f), delayMillis = 500)
            advanceEventTime(200)
            up()
        }
        compose.runOnIdle { assertEquals("hot", model!!.state.value.tabs.first().key) }
        val actions = compose.onNodeWithTag("home-tabs-drag-hot").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first { it.label == "下移" }.action()) }
        compose.runOnIdle { assertEquals(defaultHomeTabs(false), model!!.state.value.tabs); assertNull(saved) }
    }

    @Test fun draggingNearTheBottomScrollsALongSelectedListAndKeepsTheDraggedIdentity() {
        val tabs = (1..24).map { HomeTab.category(Category(it, "分类 $it", "category-$it", "", "", 0)) }
        show(tabs)
        val first = compose.onNodeWithTag("home-tabs-drag-category:1").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("home-tabs-list").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("home-tabs-drag-category:1").performTouchInput {
            val destination = center + Offset(0f, viewport.bottom - first.center.y - 30f)
            down(center)
            advanceEventTime(700)
            moveTo(destination, delayMillis = 600)
            repeat(12) { moveTo(destination, delayMillis = 100) }
            up()
        }
        compose.runOnIdle {
            assertTrue(model!!.state.value.tabs.indexOfFirst { it.key == "category:1" } > 5)
            assertEquals(tabs.map { it.key }.toSet(), model!!.state.value.tabs.map { it.key }.toSet())
            assertNull(saved)
        }
        capture("home-tabs-editor-drag-long-list.png")
    }

    @Test fun aTagThatGainsAnIdStillAppearsAsAlreadyAdded() {
        show(listOf(HomeTab.filter(FeedFilter.LATEST), HomeTab.tag(TopicTag("公告", "444-tag"))))
        compose.onNodeWithTag("home-tabs-source-TAG").performScrollTo().performClick()
        compose.onNodeWithTag("home-tabs-query").performScrollTo().performTextReplacement("公告")
        compose.onNodeWithTag("home-tabs-query").performImeAction()
        compose.waitUntil(5000) { !model!!.state.value.tagsLoading && model!!.state.value.tags.isNotEmpty() }
        compose.onNodeWithTag("home-tabs-add-tag:444").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(2, model!!.state.value.tabs.size)
            assertEquals("tag:444-tag", model!!.state.value.tabs.last().key)
            assertFalse(model!!.state.value.dirty)
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("home-tabs-editor").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
