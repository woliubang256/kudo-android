package com.kudo.android

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.*
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class EditorMentionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val neo = Member(1, "neo", "站长", "")
    private val neyuki = Member(2, "neyuki", "雪", "")

    @Composable private fun HostedTheme(content: @Composable () -> Unit) {
        KudoTheme(DisplaySettings()) { EditorToolBackdropHost(content) }
    }

    @Before fun window() { compose.runOnUiThread {
        compose.activity.enableEdgeToEdge()
        compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    } }
    private fun body() = compose.onNodeWithTag("editor-body")
    private fun waitUser(username: String = "neo") = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag("mention-user-$username").fetchSemanticsNodes().isNotEmpty()
    }
    private fun keyboardShown() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun topicCompletionPreservesSurroundingDraftCursorAndKeyboardWithoutPublishing() {
        var draft by mutableStateOf(Draft(title = "标题", categoryId = 1, raw = "前文  后文"))
        val terms = CopyOnWriteArrayList<String>()
        compose.setContent { HostedTheme {
            EditorForm(draft, emptyList(), { draft = it }, { fail("Unexpected publish") }, {}, {},
                mentionSearch = { terms.add(it); listOf(neo, neyuki) })
        } }
        body().performScrollTo().performClick().performTextInputSelection(TextRange(3))
        body().performTextInput("@ne")
        waitUser()
        capture("mentions-topic-light.png")
        compose.onNodeWithTag("mention-user-neo").assertHeightIsAtLeast(48.dp).performClick()
        body().assertTextEquals("前文 @neo 后文").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(8)))
        assertEquals("前文 @neo 后文", draft.raw)
        assertEquals(listOf("ne"), terms.toList())
        compose.waitUntil { keyboardShown() }
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
    }

    @Test fun replySuggestionsUseWindowSpaceWithDarkLargeTextAndKeepTheKeyboard() {
        var draft by mutableStateOf(Draft(raw = ""))
        val visible = MutableTransitionState(false)
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                EditorToolBackdropHost {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        ReplyEditorSheet(visible, false, { visible.targetState = false }) {
                            Box(Modifier.width(280.dp)) {
                                EditorForm(draft, emptyList(), { draft = it }, { fail("Unexpected publish") }, {}, {}, reply = true,
                                    replyRules = ReplyRules(1, 64000),
                                    mentionSearch = { listOf(neo, neyuki, neo.copy(id = 3, username = "very_long_username_for_layout", name = "较长的昵称")) })
                            }
                        }
                    }
                }
            }
        } }
        compose.runOnIdle { visible.targetState = true }
        compose.waitUntil { keyboardShown() }
        body().performTextInput("@")
        waitUser()
        compose.onNodeWithTag("mention-user-neyuki").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        body().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val caret = layouts.single().getCursorRect(1).translate(body().fetchSemanticsNode().positionOnScreen).inflate(3f)
        val panel = compose.onNodeWithTag("mention-suggestions").fetchSemanticsNode()
        val bounds = Rect(panel.positionOnScreen, panel.size.toSize())
        assertFalse("Candidates $bounds must not cover the caret $caret", bounds.overlaps(caret))
        val visibleFrame = android.graphics.Rect().also { compose.activity.window.decorView.getWindowVisibleDisplayFrame(it) }
        assertTrue("Candidates must stay above the keyboard", bounds.bottom <= visibleFrame.bottom + 1f)
        capture("mentions-reply-dark-large.png")
        compose.onNodeWithTag("mention-user-neyuki").performClick()
        assertEquals("@neyuki ", draft.raw)
        body().assertIsFocused()
        assertTrue(keyboardShown())
    }

    @Test fun lateCancelledQueryCannotReplaceNewerSuggestions() {
        var draft by mutableStateOf(Draft(title = "标题", categoryId = 1))
        val oldStarted = AtomicBoolean()
        val oldFinished = AtomicBoolean()
        val release = CompletableDeferred<Unit>()
        compose.setContent { HostedTheme {
            EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, mentionSearch = { term ->
                if (term == "n") withContext(NonCancellable) {
                    oldStarted.set(true); release.await(); oldFinished.set(true); listOf(neyuki)
                } else listOf(neo)
            })
        } }
        body().performScrollTo().performClick().performTextInput("@n")
        compose.waitUntil { oldStarted.get() }
        body().performTextReplacement("@ne")
        waitUser()
        release.complete(Unit)
        compose.waitUntil { oldFinished.get() }
        compose.onNodeWithTag("mention-user-neyuki").assertDoesNotExist()
        compose.onNodeWithTag("mention-user-neo").performClick()
        assertEquals("@neo ", draft.raw)
    }

    @Test fun typingAndDeletingLettersRefreshesAnAlreadyVisibleCandidateList() {
        var draft by mutableStateOf(Draft(title = "标题", categoryId = 1))
        val visible = MutableTransitionState(false)
        val terms = CopyOnWriteArrayList<String>()
        val recent = Member(3, "recent_member", "最近用户", "")
        compose.setContent { HostedTheme {
            ReplyEditorSheet(visible, false, { visible.targetState = false }) {
                EditorForm(draft, emptyList(), { draft = it }, { fail("Unexpected publish") }, {}, {}, reply = true,
                    replyRules = ReplyRules(1, 64000), mentionSearch = { term ->
                        terms.add(term)
                        when (term) {
                            "" -> listOf(recent)
                            "n" -> listOf(neyuki)
                            "ne" -> listOf(neo)
                            else -> emptyList()
                        }
                    })
            }
        } }
        compose.runOnIdle { visible.targetState = true }
        compose.waitUntil { keyboardShown() }
        body().performTextInput("@")
        waitUser("recent_member")
        body().performTextInput("n")
        body().assertTextEquals("@n")
        waitUser("neyuki")
        compose.onNodeWithTag("mention-user-recent_member").assertDoesNotExist()
        body().performTextInput("e")
        waitUser()
        compose.onNodeWithTag("mention-user-neyuki").assertDoesNotExist()
        body().performTextReplacement("@zzzzmissing")
        compose.waitUntil { compose.onAllNodesWithText("没有匹配用户").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mention-user-neo").assertDoesNotExist()
        body().performTextReplacement("@n")
        waitUser("neyuki")
        body().performTextReplacement("@")
        waitUser("recent_member")
        assertEquals(listOf("", "n", "ne", "zzzzmissing", "n", ""), terms.toList())
        body().assertIsFocused().assertTextEquals("@")
        assertEquals("@", draft.raw)
    }

    @Test fun latinImeCompositionRefreshesVisibleCandidatesBeforeTheWordIsCommitted() {
        var draft by mutableStateOf(Draft(title = "标题", categoryId = 1))
        val visible = MutableTransitionState(false)
        val recent = Member(3, "recent_member", "最近用户", "")
        val chinese = Member(4, "xiaoming", "小明", "")
        compose.setContent { HostedTheme {
            ReplyEditorSheet(visible, false, { visible.targetState = false }) {
                EditorForm(draft, emptyList(), { draft = it }, { fail("Unexpected publish") }, {}, {}, reply = true,
                    replyRules = ReplyRules(1, 64000), mentionSearch = { term ->
                        when (term) {
                            "" -> listOf(recent)
                            "n" -> listOf(neyuki)
                            "ne" -> listOf(neo)
                            "小明" -> listOf(chinese)
                            else -> emptyList()
                        }
                    })
            }
        } }
        compose.runOnIdle { visible.targetState = true }
        compose.waitUntil { keyboardShown() }
        body().performTextInput("@")
        waitUser("recent_member")
        val connection = compose.runOnUiThread {
            compose.activity.currentFocus!!.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
        }
        compose.runOnUiThread { connection.setComposingText("n", 1) }
        waitUser("neyuki")
        body().assertTextEquals("@n")
        compose.runOnUiThread {
            connection.setComposingRegion(1, 2)
            connection.setComposingText("ne", 1)
        }
        body().assertTextEquals("@ne")
        waitUser()
        compose.onNodeWithTag("mention-user-neyuki").assertDoesNotExist()
        assertEquals("@ne", draft.raw)
        compose.onNodeWithTag("mention-user-neo").performClick()
        body().assertIsFocused().assertTextEquals("@neo ")
        assertEquals("@neo ", draft.raw)
        assertTrue(keyboardShown())
        body().performTextReplacement("@小明")
        waitUser("xiaoming")
        compose.onNodeWithTag("mention-user-xiaoming").performClick()
        body().assertIsFocused().assertTextEquals("@xiaoming ")
        assertEquals("@xiaoming ", draft.raw)
    }

    @Test fun movingCursorOrSendingClosesSuggestionsAndRetryDoesNotChangeTheDraft() {
        var draft by mutableStateOf(Draft(title = "标题", categoryId = 1))
        var sending by mutableStateOf(false)
        var requests = 0
        compose.setContent { HostedTheme {
            EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, sending = sending, mentionSearch = {
                if (++requests == 1) error("离线夹具错误") else listOf(neo)
            })
        } }
        body().performScrollTo().performClick().performTextInput("@ne")
        compose.waitUntil { compose.onAllNodesWithText("离线夹具错误").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("重试").performClick()
        waitUser()
        assertEquals("@ne", draft.raw)
        body().performTextInputSelection(TextRange.Zero)
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
        body().performTextInputSelection(TextRange(3))
        waitUser()
        compose.runOnIdle { sending = true }
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
        assertEquals("@ne", draft.raw)
    }

    @Test fun backDismissesCandidatesAndPreviewDoesNotLeaveAListBehind() {
        var draft by mutableStateOf(Draft(title = "标题", categoryId = 1))
        compose.setContent { HostedTheme {
            EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, mentionSearch = { listOf(neo) })
        } }
        body().performScrollTo().performClick().performTextInput("@ne")
        waitUser()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
        body().performTextInput("o")
        waitUser()
        compose.onNodeWithContentDescription("预览正文").performScrollTo().performClick()
        compose.onNodeWithTag("markdown-preview").assertIsDisplayed()
        compose.onNodeWithTag("mention-suggestions").assertDoesNotExist()
        assertEquals("@neo", draft.raw)
    }
}
