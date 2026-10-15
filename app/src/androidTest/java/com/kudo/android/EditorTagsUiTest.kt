package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.EditorForm
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EditorTagsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val categories = listOf(Category(4, "开发调优", "dev", "", "", 0), Category(98, "日常闲聊", "chat", "", "", 0))
    private val tags = listOf(TopicTag("公告", id = 444), TopicTag("开发", id = 445), TopicTag("分享", id = 446))
    private var draft by mutableStateOf(Draft("标题", "正文", 4))
    private var sending by mutableStateOf(false)
    private var published = 0

    private fun show(dark: Boolean = false, narrow: Boolean = false, reply: Boolean = false, editing: Boolean = false,
        search: suspend (String, Int, List<TopicTag>) -> List<TopicTag> = { query, _, _ -> tags.filter { it.name.contains(query) } }) {
        compose.setContent {
            KudoTheme(DisplaySettings(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, fontScale = if (narrow) 1.3f else 1f)) {
                Box(if (narrow) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                    EditorForm(draft, categories, { draft = it }, { published++ }, {}, {}, sending = sending,
                        reply = reply, editing = editing, replyRules = ReplyRules(1, 64000), tagSearch = search)
                }
            }
        }
    }

    private fun awaitTag(name: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("editor-tag-$name").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun categoryAndTagsShareOneRowAndMultipleSelectionsOnlyApplyOnConfirmation() {
        show()
        val category = compose.onNodeWithTag("editor-category").fetchSemanticsNode().boundsInRoot
        val tag = compose.onNodeWithTag("editor-tags").fetchSemanticsNode().boundsInRoot
        assertEquals(category.top, tag.top, .1f)
        assertEquals(category.bottom, tag.bottom, .1f)
        assertTrue(category.right <= tag.left)
        compose.onNodeWithText("发布到").assertDoesNotExist()
        compose.onNodeWithTag("editor-tags").performClick()
        awaitTag("公告")
        compose.onNodeWithTag("editor-tag-公告").performClick().assertIsOn()
        awaitTag("开发")
        compose.onNodeWithTag("editor-tag-开发").performClick().assertIsOn()
        compose.runOnIdle { assertTrue(draft.tags.isEmpty()); assertEquals(0, published) }
        compose.onNodeWithTag("editor-title").assertDoesNotExist()
        compose.onNodeWithText("发布", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("editor-tags-confirm").performClick()
        compose.onNodeWithText("已选 2 个").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("公告", "开发"), draft.tags) }
        compose.onNodeWithText("发布", substring = false).assertIsEnabled()
    }

    @Test fun cancelAndDragDismissDoNotChangeStoredSelectionsAndReopeningResetsPendingChanges() {
        draft = draft.copy(tags = listOf("公告"))
        show()
        compose.onNodeWithTag("editor-tags").performClick()
        awaitTag("公告")
        compose.onNodeWithTag("editor-tag-公告").performClick()
        awaitTag("开发")
        compose.onNodeWithTag("editor-tag-开发").performClick()
        compose.onNodeWithTag("editor-tags-cancel").performClick()
        compose.runOnIdle { assertEquals(listOf("公告"), draft.tags) }
        compose.onNodeWithTag("editor-tags").performClick()
        awaitTag("公告")
        compose.onNodeWithTag("editor-tag-公告").assertIsOn()
        awaitTag("开发")
        compose.onNodeWithTag("editor-tag-开发").assertIsOff()
        compose.onNodeWithTag("sheet-drag-handle").performTouchInput { swipe(center, center + Offset(0f, 600f), 600) }
        compose.onNodeWithTag("editor-title").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("公告"), draft.tags) }
    }

    @Test fun categoryChangeClearsIncompatibleTagsButReselectingSameCategoryKeepsThem() {
        draft = draft.copy(tags = listOf("公告"))
        show()
        compose.onNodeWithTag("editor-category").performClick()
        compose.onNodeWithText("开发调优").performClick()
        compose.runOnIdle { assertEquals(listOf("公告"), draft.tags) }
        compose.onNodeWithTag("editor-category").performClick()
        compose.onNodeWithText("日常闲聊").performClick()
        compose.runOnIdle { assertEquals(98, draft.categoryId); assertTrue(draft.tags.isEmpty()) }
    }

    @Test fun failedSearchCanRetryWithoutDiscardingSelectedTags() {
        draft = draft.copy(tags = listOf("公告"))
        val attempts = AtomicInteger()
        show { _, category, selected ->
            assertEquals(4, category)
            assertEquals(listOf("公告"), selected.map { it.name })
            if (attempts.incrementAndGet() == 1) error("标签夹具失败")
            tags
        }
        compose.onNodeWithTag("editor-tags").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("标签夹具失败").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-tag-公告").assertIsOn()
        compose.onNodeWithText("重试").performClick()
        awaitTag("开发")
        compose.onNodeWithText("标签夹具失败").assertDoesNotExist()
        compose.onNodeWithTag("editor-tags-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("公告"), draft.tags) }
    }

    @Test fun latestQueryWinsEvenIfPreviousServerCallIgnoresCancellation() {
        val pending = CompletableDeferred<List<TopicTag>>()
        val started = AtomicBoolean()
        val returned = AtomicBoolean()
        show { query, _, _ -> when (query) {
            "旧" -> withContext(NonCancellable) { started.set(true); pending.await().also { returned.set(true) } }
            "新" -> listOf(TopicTag("新结果"))
            else -> emptyList()
        } }
        compose.onNodeWithTag("editor-tags").performClick()
        compose.onNodeWithTag("editor-tag-query").performTextReplacement("旧")
        compose.waitUntil(5000) { started.get() }
        compose.onNodeWithTag("editor-tag-query").performTextReplacement("新")
        awaitTag("新结果")
        pending.complete(listOf(TopicTag("旧结果")))
        compose.waitUntil(5000) { returned.get() }
        compose.waitForIdle()
        compose.onNodeWithTag("editor-tag-新结果").assertIsDisplayed()
        compose.onNodeWithTag("editor-tag-旧结果").assertDoesNotExist()
    }

    @Test fun cancelledRequestCannotHideLoadingOfNewQuery() {
        val old = CompletableDeferred<List<TopicTag>>()
        val latest = CompletableDeferred<List<TopicTag>>()
        val oldStarted = AtomicBoolean()
        val latestStarted = AtomicBoolean()
        val oldReturned = AtomicBoolean()
        show { query, _, _ -> when (query) {
            "旧" -> withContext(NonCancellable) { oldStarted.set(true); old.await().also { oldReturned.set(true) } }
            "新" -> { latestStarted.set(true); latest.await() }
            else -> emptyList()
        } }
        compose.onNodeWithTag("editor-tags").performClick()
        compose.onNodeWithTag("editor-tag-query").performTextReplacement("旧")
        compose.waitUntil(5000) { oldStarted.get() }
        compose.onNodeWithTag("editor-tag-query").performTextReplacement("新")
        compose.waitUntil(5000) { latestStarted.get() }
        old.complete(listOf(TopicTag("旧结果")))
        compose.waitUntil(5000) { oldReturned.get() }
        compose.waitForIdle()
        compose.onNodeWithText("正在加载标签…").assertIsDisplayed()
        latest.complete(listOf(TopicTag("新结果")))
        awaitTag("新结果")
    }

    @Test fun narrowDarkLargeTextKeepsBothDestinationsAndSheetActionsVisible() {
        draft = draft.copy(tags = listOf("公告", "开发", "分享"))
        show(dark = true, narrow = true)
        compose.onNodeWithTag("editor-category").assertIsDisplayed()
        compose.onNodeWithTag("editor-tags").assertIsDisplayed()
        capture("composer-destinations-dark-narrow.png")
        compose.onNodeWithTag("editor-tags").performClick()
        awaitTag("公告")
        compose.onNodeWithTag("editor-tags-confirm").assertIsDisplayed()
        compose.onNodeWithTag("editor-tags-cancel").assertIsDisplayed()
        capture("composer-tags-dark-narrow.png")
    }

    @Test fun sendingDisablesDestinations() {
        sending = true
        show()
        compose.onNodeWithTag("editor-category").assertIsNotEnabled()
        compose.onNodeWithTag("editor-tags").assertIsNotEnabled()
        compose.onNodeWithTag("editor-tags").performClick()
        compose.onNodeWithTag("editor-tag-query").assertDoesNotExist()
    }

    @Test fun tagsAreUnavailableUntilCategoryIsChosen() {
        draft = draft.copy(categoryId = 0)
        show()
        compose.onNodeWithTag("editor-tags").assertIsNotEnabled()
        compose.onNodeWithTag("editor-category").performClick()
        compose.onNodeWithText("开发调优").performClick()
        compose.onNodeWithTag("editor-tags").assertIsEnabled()
    }

    @Test fun repliesDoNotExposeTopicTags() {
        show(reply = true)
        compose.onNodeWithTag("editor-tags").assertDoesNotExist()
        compose.onNodeWithTag("editor-category").assertDoesNotExist()
    }

    @Test fun postEditingDoesNotExposeTopicTags() {
        show(editing = true)
        compose.onNodeWithTag("editor-tags").assertDoesNotExist()
        compose.onNodeWithTag("editor-category").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
