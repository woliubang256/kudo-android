package com.kudo.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.kudo.android.data.*
import com.kudo.android.ui.feed.*
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Rule
import org.junit.Test

class TagDestinationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun knownTagNameRemainsVisibleWhenContentIsUnavailable() {
        compose.setContent { KudoTheme(DisplaySettings()) {
            TagFeedScreen("444-tag", FeedState(loading = false, error = ApiFailure(FailureKind.NOT_FOUND, "不存在")),
                null, {}, {}, {}, {}, {}, {}, {}, {}, TopicListStyle.FEED, emptyMap(), name = "公告")
        } }
        compose.onNodeWithTag("tag-toolbar-name").assertTextEquals("公告")
        compose.onNodeWithText("444-tag").assertDoesNotExist()
    }

    @Test fun externalTagTitleUsesReturnedNameInsteadOfGeneratedSlug() {
        var state by mutableStateOf(FeedState())
        compose.setContent { KudoTheme(DisplaySettings()) {
            TagFeedScreen("444-tag", state, null, {}, {}, {}, {}, {}, {}, {}, {}, TopicListStyle.FEED, emptyMap())
        } }
        compose.onNodeWithTag("tag-toolbar-name").assertTextEquals("标签")
        compose.runOnIdle { state = FeedState(loading = false, tag = TopicTag("公告", "444-tag", id = 444)) }
        compose.onNodeWithTag("tag-toolbar-name").assertTextEquals("公告")
    }
}
