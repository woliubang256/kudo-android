package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.*
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import java.io.File

class PostEditingUiTest {
    @get:Rule val compose = createComposeRule()
    private val editors = mutableListOf<PostEditViewModel>()
    private val author = Member(2, "me", "我", "")
    private val post = Post(81, 8, author, "正文", "", 0, false, null, null, false, canEdit = true)
    @After fun clean() { compose.runOnIdle { editors.forEach { it.viewModelScope.cancel() } } }
    private fun capture(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun replyEditsUseFullPageAndSaveRawWithoutTopicFields() {
        lateinit var vm: PostEditViewModel
        var captured: PostEditDraft? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = PostEditViewModel({ EditablePost(post, 5, "原始回复 ![图](upload://a)", "") }, { _, draft -> captured = draft; post.copy(version = 2) })
            editors += vm
        }
        compose.setContent { KudoTheme(DisplaySettings(glass = GlassMode.TRANSLUCENT)) {
            PostEditScreen(vm, GlassMode.TRANSLUCENT, { "" }, {}, {})
        } }
        compose.waitUntil(5000) { !vm.loading }
        compose.onNodeWithText("编辑回复").assertIsDisplayed()
        compose.onNodeWithTag("editor-title").assertDoesNotExist()
        compose.onNodeWithTag("editor-category").assertDoesNotExist()
        compose.onNodeWithTag("editor-body").assertTextContains("原始回复 ![图](upload://a)")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("editor-body").performTextReplacement("更新回复 ![图](upload://a)")
        capture("post-edit-reply-light.png")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { captured != null }
        assertEquals("更新回复 ![图](upload://a)", captured!!.raw)
        assertEquals("原始回复 ![图](upload://a)", captured!!.originalRaw)
    }
    @Test fun mainPostCanSaveATitleChangeWithoutChangingBody() {
        lateinit var vm: PostEditViewModel
        var captured: PostEditDraft? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = PostEditViewModel({ EditablePost(post.copy(number = 1), 5, "主帖原文", "原标题") }, { _, draft -> captured = draft; post.copy(number = 1, version = 2) })
            editors += vm
        }
        compose.setContent { KudoTheme(DisplaySettings(glass = GlassMode.TRANSLUCENT)) { PostEditScreen(vm, GlassMode.TRANSLUCENT, { "" }, {}, {}) } }
        compose.waitUntil(5000) { !vm.loading }
        compose.onNodeWithText("编辑话题").assertIsDisplayed()
        compose.onNodeWithTag("editor-title").performTextReplacement("更新标题")
        capture("post-edit-topic-light.png")
        compose.onNodeWithText("保存").performClick(); compose.waitUntil(5000) { captured != null }
        assertEquals("更新标题", captured!!.title); assertEquals("主帖原文", captured!!.raw)
    }
    @Test fun editingDraftStorageIsIsolatedFromPublishingAndOtherAccounts() {
        val settings = AppSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val user = Int.MIN_VALUE; val target = 999981
        val draft = PostEditDraft("old", "title", "new", "title")
        try {
            settings.saveDraft("${user}_new_0", Draft(raw = "发帖草稿"))
            settings.savePostEditDraft(user, target, draft)
            assertEquals(draft, settings.postEditDraft(user, target))
            assertNull(settings.postEditDraft(user + 1, target))
            assertEquals("发帖草稿", settings.draft("${user}_new_0").raw)
        } finally { settings.savePostEditDraft(user, target, null); settings.clearDraft("${user}_new_0") }
    }
}
