package com.kudo.android

import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.SettingsScreen
import com.kudo.android.ui.components.TopicCard
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NsfwImageSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nsfwTopicPreviewBlursByDefaultAndRestoresWhenDisabled() {
        val nsfw = Topic(71, "敏感图片", "", null, 1, listOf("NSFW"), 0, 0, 0,
            "https://fixture.test/nsfw.png", "2026-10-18T00:00:00Z")
        val ordinary = nsfw.copy(id = 72, title = "普通图片", tags = listOf("公告"), image = "https://fixture.test/ordinary.png")
        var blur by mutableStateOf(true)
        var showOrdinary by mutableStateOf(false)
        compose.setContent { KudoTheme(DisplaySettings(blurNsfwImages = blur)) {
            TopicCard(if (showOrdinary) ordinary else nsfw, null, {}, {})
        } }
        compose.onNodeWithTag("topic-image-71-blurred", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { blur = false }
        compose.onNodeWithTag("topic-image-71-sharp", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("topic-image-71-blurred", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { showOrdinary = true }
        compose.onNodeWithTag("topic-image-72-sharp", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun nsfwImageSettingDefaultsOnAndPersistsTheUserChoice() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "nsfw_image_fixture_${System.nanoTime()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(preferenceName, mode)
        }
        try {
            assertTrue(DisplaySettings().blurNsfwImages)
            var store by mutableStateOf(AppSettings(context))
            assertTrue(store.display.value.blurNsfwImages)
            compose.setContent {
                val settings by store.display.collectAsState()
                KudoTheme(settings) { SettingsScreen(settings, store::update, null, {}, {}, {}) }
            }
            compose.onNodeWithTag("blur-nsfw-images").performScrollTo().assertIsOn().performClick().assertIsOff()
            compose.runOnIdle {
                store = AppSettings(context)
                assertFalse(store.display.value.blurNsfwImages)
            }
        } finally {
            target.deleteSharedPreferences(preferenceName)
        }
    }
}
