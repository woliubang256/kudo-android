package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.EditorToolBackdropHost
import com.kudo.android.ui.library.*
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ImageLibraryFolderOrderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "library_order_${UUID.randomUUID()}"
    private var failWrite = false
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
            val preferences = base.getSharedPreferences(name, mode)
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                        override fun commit(): Boolean = !failWrite && editor.commit()
                    }
                }
            }
        }
    }
    private val store = ImageLibraryStore(context)

    @Before fun seed() = runBlocking { store.update(101) { it.addFolder("表情", "emoji").addFolder("风景", "landscape") }; Unit }
    @After fun cleanup() { base.deleteSharedPreferences(name) }
    private fun order() = runBlocking { store.load(101).folders.map(ImageFolder::id) }
    private fun show(dark: Boolean = false, large: Boolean = false, picker: State<Boolean>? = null) {
        compose.setContent { KudoTheme(DisplaySettings(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f)) {
                ImageLibraryEnvironment(store, 101, true) {
                    if (picker?.value == true) EditorToolBackdropHost { Column { ImageLibraryPicker(true) {} } }
                    else Box(Modifier.fillMaxSize()) { ImageLibraryScreen({}) {} }
                }
            }
        } }
        compose.waitUntil { compose.onAllNodes(hasContentDescription("管理图库目录") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("管理图库目录").performClick()
    }
    private fun move(id: String, action: String) {
        val actions = compose.onNodeWithTag("library-folder-drag-$id").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first { it.label == action }.action()) }
    }
    private fun close() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { compose.onAllNodesWithTag("liquid-action-sheet").fetchSemanticsNodes().isEmpty() }
    }
    private fun assertTabOrder() {
        val labels = listOf("全部", "表情", "风景", "默认收藏")
        val x = labels.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.center.x }
        assertTrue("Tab order must use persisted folder order: $x", x.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun draggingDefaultPersistsAndManagementAndPickerUseTheSameOrder() {
        val picker = mutableStateOf(false)
        show(picker = picker)
        val first = compose.onNodeWithTag("library-folder-drag-default").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("library-folder-drag-landscape").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("library-folder-drag-default").performTouchInput {
            down(center); advanceEventTime(700)
            moveTo(center + Offset(0f, last.center.y - first.center.y + 15f), delayMillis = 500)
            advanceEventTime(200); up()
        }
        compose.waitUntil(5_000) { order() == listOf("emoji", "landscape", DEFAULT_IMAGE_FOLDER) }
        compose.onNodeWithTag("liquid-action-sheet").assertIsDisplayed()
        close()
        assertTabOrder()
        compose.runOnIdle { picker.value = true }
        compose.onNodeWithTag("editor-library-picker").assertIsDisplayed()
        assertTabOrder()
        assertEquals(order(), runBlocking { ImageLibraryStore(context).load(101).folders.map(ImageFolder::id) })
    }

    @Test fun accessibilityMovesFoldersAndSaveFailureRestoresTheSavedOrder() {
        show(dark = true, large = true)
        move("emoji", "上移")
        compose.waitUntil(5_000) { order() == listOf("emoji", DEFAULT_IMAGE_FOLDER, "landscape") }
        compose.waitUntil { compose.onAllNodes(hasTestTag("library-folder-drag-default") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        failWrite = true
        move("landscape", "上移")
        compose.waitUntil { compose.onAllNodesWithText("图库保存失败，请检查存储空间后重试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("图库保存失败，请检查存储空间后重试").assertIsDisplayed()
        val middle = compose.onNodeWithTag("library-folder-row-default").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("library-folder-row-landscape").fetchSemanticsNode().boundsInRoot
        assertTrue(middle.center.y < last.center.y)
        assertEquals(listOf("emoji", DEFAULT_IMAGE_FOLDER, "landscape"), order())
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(base.getExternalFilesDir(null), "library-folder-order-dark-large.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test fun edgeDraggingScrollsLongListsAndCancelDoesNotSavePreviewOrder() {
        runBlocking { store.update(101) { library -> (1..22).fold(library) { result, index -> result.addFolder("目录$index", "extra-$index") } } }
        val before = order()
        show()
        val first = compose.onNodeWithTag("library-folder-drag-default").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("library-folder-list").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("library-folder-drag-default").performTouchInput {
            val end = center + Offset(0f, viewport.bottom - first.center.y - 20f)
            down(center); advanceEventTime(700); moveTo(end, delayMillis = 600)
            repeat(12) { moveTo(end, delayMillis = 100) }
            up()
        }
        compose.waitUntil(5_000) { order().indexOf(DEFAULT_IMAGE_FOLDER) > 5 }
        assertEquals(before.toSet(), order().toSet())
        compose.onNodeWithTag("library-folder-list").performScrollToIndex(0)
        val persisted = order()
        val id = persisted.first()
        compose.onNodeWithTag("library-folder-drag-$id").performTouchInput {
            down(center); advanceEventTime(700); moveTo(center + Offset(0f, 100f), delayMillis = 500); cancel()
        }
        compose.runOnIdle { assertEquals(persisted, order()) }
    }
}
