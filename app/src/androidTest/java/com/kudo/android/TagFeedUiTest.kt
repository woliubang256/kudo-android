package com.kudo.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.kudo.android.data.*
import com.kudo.android.ui.feed.FeedState
import com.kudo.android.ui.feed.TagFeedScreen
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Rule
import org.junit.Test

class TagFeedUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tagStartsWithYellowCompactToolbarAndTopicList() {
        val state = FeedState(filter = "latest", loading = false, topics = listOf(
            Topic(31, "标签中的话题", "摘要", null, 0, emptyList(), 2, 10, 1, "", "")
        ))
        compose.setContent {
            KudoTheme(DisplaySettings()) {
                TagFeedScreen("193-tag", state, null, {}, {}, {}, {}, {}, {}, {}, {}, TopicListStyle.TITLE, emptyMap())
            }
        }
        compose.onNodeWithTag("tag-toolbar").assertIsDisplayed()
        compose.onNodeWithTag("tag-toolbar-name").assertTextEquals("标签")
        compose.onNodeWithContentDescription("标签").assertIsDisplayed()
        compose.onNodeWithTag("feed-list-latest").assertIsDisplayed()
        compose.onNodeWithText("标签中的话题").assertIsDisplayed()
    }
}
