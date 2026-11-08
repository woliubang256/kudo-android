package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.kudo.android.data.*
import com.kudo.android.ui.components.LocalAvatarMetadata
import com.kudo.android.ui.editor.EditorEmojis
import com.kudo.android.ui.theme.KudoTheme
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Uses an isolated preference file and an offline directory, never the user's recent emoji list. */
class EditorEmojiIdentityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cache: AvatarMetadataCache? = null
    private val inserted = mutableListOf<String>()
    private var loads = 0

    @Before fun isolatedPreferences() {
        val activity = compose.activity
        val file = "emoji-identity-test-${UUID.randomUUID()}"
        context = object : ContextWrapper(activity) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                activity.getSharedPreferences("$file-$name", mode)
        }
        preferences = context.getSharedPreferences("kudo_preferences", Context.MODE_PRIVATE)
    }

    @After fun releaseFixture() {
        cache?.close()
        scope.cancel()
        preferences.edit().clear().commit()
    }

    private fun show(groups: List<SiteEmojiGroup>) {
        val metadata = AvatarMetadataCache(scope, { emptyList() }, { emptyMap() },
            loadEmojiGroups = { loads++; groups }).also { cache = it }
        compose.setContent { KudoTheme(DisplaySettings()) {
            CompositionLocalProvider(LocalContext provides context, LocalAvatarMetadata provides metadata) {
                Column(Modifier.fillMaxWidth()) {
                    EditorEmojis { inserted += it }
                }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-emoji-tab-0").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test fun duplicateDirectoryEntriesAreSafeInGroupsAndSearch() {
        val emoji = SiteEmoji("same", "", "custom")
        show(listOf(SiteEmojiGroup("custom", listOf(emoji, emoji, emoji.copy(name = "alias")))))
        compose.onNodeWithTag("editor-emoji-tab-0").performClick()
        compose.onAllNodesWithContentDescription(":same:").assertCountEquals(1)
        compose.onNodeWithTag("editor-emoji-search").performTextReplacement("same")
        compose.onAllNodesWithContentDescription(":same:").assertCountEquals(1)
        compose.onNodeWithContentDescription(":same:").performClick()
        compose.runOnIdle { assertEquals(listOf(":same:"), inserted); assertEquals(1, loads) }
    }

    @Test fun historicalRecentDuplicatesCannotCreateDuplicateGridKeys() {
        preferences.edit().putString("editor_recent_emojis", "wave,wave,alias,wave").commit()
        show(listOf(SiteEmojiGroup("custom", listOf(SiteEmoji("wave", "", "custom"), SiteEmoji("alias", "", "custom")))))
        compose.onAllNodesWithContentDescription(":wave:").assertCountEquals(1)
        compose.onNodeWithContentDescription(":alias:").assertIsDisplayed()
        compose.onNodeWithContentDescription(":wave:").performClick()
        compose.runOnIdle { assertEquals(listOf(":wave:"), inserted) }
    }

    @Test fun crossGroupNamesAndAliasesSurviveSearchAndRepeatedGroupSwitches() {
        show(listOf(
            SiteEmojiGroup("standard", listOf(SiteEmoji("wave", "", "standard"), SiteEmoji("wave_alias", "", "standard"))),
            SiteEmojiGroup("custom", listOf(SiteEmoji("wave", "", "custom"))),
        ))
        repeat(4) {
            compose.onNodeWithTag("editor-emoji-search").performTextReplacement("wave")
            compose.onAllNodesWithContentDescription(":wave:").assertCountEquals(2)
            compose.onNodeWithContentDescription(":wave_alias:").assertIsDisplayed()
            compose.onNodeWithTag("editor-emoji-tab-0").performClick()
            compose.onNodeWithTag("editor-emoji-search")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithTag("editor-emoji-pager").performTouchInput { swipeLeft(durationMillis = 400) }
            compose.onNodeWithTag("editor-emoji-tab-1").assertIsSelected()
            compose.onNodeWithTag("editor-emoji-pager").performTouchInput { swipeRight(durationMillis = 400) }
            compose.onNodeWithTag("editor-emoji-tab-0").assertIsSelected()
            compose.onNodeWithTag("editor-emoji-tab-recent").performClick()
        }
        compose.onNodeWithTag("editor-emoji-search").performTextReplacement("wave_alias")
        compose.onNodeWithContentDescription(":wave_alias:").performClick()
        compose.onNodeWithTag("editor-emoji-search").performTextClearance()
        compose.onNodeWithContentDescription(":wave_alias:").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(":wave_alias:"), inserted); assertEquals(1, loads) }
    }

    @Test fun groupAndNameSeparatorsDoNotCollideInSearchKeys() {
        show(listOf(
            SiteEmojiGroup("a:b", listOf(SiteEmoji("c", "", "a:b"))),
            SiteEmojiGroup("a", listOf(SiteEmoji("b:c", "", "a"))),
        ))
        compose.onNodeWithTag("editor-emoji-search").performTextReplacement("c")
        compose.onNodeWithContentDescription(":c:").assertIsDisplayed()
        compose.onNodeWithContentDescription(":b:c:").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(":b:c:"), inserted) }
    }

    @Test fun serverGroupNamedRecentCannotCollideWithTheRecentPage() {
        preferences.edit().putString("editor_recent_emojis", "wave").commit()
        show(listOf(SiteEmojiGroup("recent", listOf(SiteEmoji("wave", "", "recent")))))
        compose.onNodeWithTag("editor-emoji-tab-0").performClick()
        compose.onNodeWithTag("editor-emoji-tab-0").assertIsSelected()
        compose.onNodeWithTag("editor-emoji-tab-recent").performClick()
        compose.onNodeWithTag("editor-emoji-tab-recent").assertIsSelected()
        compose.onNodeWithContentDescription(":wave:").assertIsDisplayed()
    }
}
