package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.feed.FeedScreen
import com.kudo.android.ui.feed.FeedState
import com.kudo.android.ui.feed.SearchScreen
import com.kudo.android.ui.feed.SearchState
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class PlainFeedActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun feedEditAndSearchFilterHaveOnePlainIconTouchTarget() {
        var searching by mutableStateOf(false)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        var edits = 0
        var metadataLoads = 0
        compose.setContent { KudoTheme(DisplaySettings(theme = theme)) {
            if (searching) SearchScreen(SearchState(), { _, _ -> }, {}, { _, _ -> }, {}, {}, loadMetadata = { metadataLoads++ })
            else FeedScreen(FeedState(loading = false), null, {}, {}, {}, {}, {}, {}, {}, {}, editTabs = { edits++ })
        } }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            compose.runOnIdle { theme = mode; searching = false }
            for (tag in listOf("home-tabs-edit", "search-filter-toggle")) {
                compose.onNodeWithTag(tag).assertTouchHeightIsEqualTo(48.dp)
                compose.onAllNodes(hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
                    .assertCountEquals(1)
                val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
                val background = pixels[2, 2]
                for ((horizontal, vertical) in listOf(.15f to .5f, .85f to .5f, .5f to .15f, .5f to .85f)) {
                    val sample = pixels[(pixels.width * horizontal).toInt(), (pixels.height * vertical).toInt()]
                    assertEquals("No separate icon background", background.red, sample.red, .015f)
                    assertEquals("No separate icon background", background.green, sample.green, .015f)
                    assertEquals("No separate icon background", background.blue, sample.blue, .015f)
                }
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                File(context.getExternalFilesDir(null), "$tag-$mode-plain.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                compose.onNodeWithTag(tag).performClick()
                compose.runOnIdle { searching = true }
            }
        }
        compose.runOnIdle { assertEquals(2, edits); assertEquals(2, metadataLoads) }
    }
}
