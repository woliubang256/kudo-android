package com.kudo.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.kudo.android.data.*
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import org.junit.Rule
import org.junit.Test

class TopicEditHistoryReturnUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun restoredTopicKeepsScrolledFloorInsteadOfReplayingInitialTarget() {
        val author = Member(2, "me", "测试作者", "", avatarDetailsLoaded = true)
        val posts = (1..20).map { number -> Post(number, number, author, "<p>第 $number 楼正文</p>", "", 0, false, null, 1.takeIf { number > 1 }, false) }
        val detail = TopicDetail(5, "返回位置回归", 1, posts, (1..20).toList(), false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KudoTheme(DisplaySettings(glass = GlassMode.TRANSLUCENT)) {
            TopicScreen(TopicState(detail, false, targetNumber = 2), GlassMode.TRANSLUCENT, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("topic-posts").performScrollToNode(hasTestTag("post-12"))
        compose.onNodeWithTag("post-12").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(1200); compose.waitForIdle()
        Thread.sleep(150)
        compose.onNodeWithTag("post-12").assertIsDisplayed()
    }
}
