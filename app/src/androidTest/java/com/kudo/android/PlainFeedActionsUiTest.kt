package com.kudo.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.feed.FeedScreen
import com.kudo.android.ui.feed.FeedState
import com.kudo.android.ui.feed.SearchScreen
import com.kudo.android.ui.feed.SearchState
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlainFeedActionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun feedEditAndSearchFilterHaveOnePlainIconTouchTarget() {
        var searching by mutableStateOf(false)
        var edits = 0
        var metadataLoads = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            if (searching) SearchScreen(SearchState(), { _, _ -> }, {}, { _, _ -> }, {}, {}, loadMetadata = { metadataLoads++ })
            else FeedScreen(FeedState(loading = false), null, {}, {}, {}, {}, {}, {}, {}, {}, editTabs = { edits++ })
        } }
        for (tag in listOf("home-tabs-edit", "search-filter-toggle")) {
            compose.onNodeWithTag(tag).assertTouchHeightIsEqualTo(48.dp)
            compose.onAllNodes(hasClickAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
                .assertCountEquals(1)
            compose.onNodeWithTag(tag).performClick()
            compose.runOnIdle { searching = true }
        }
        compose.runOnIdle { assertEquals(1, edits); assertEquals(1, metadataLoads) }
    }
}
