package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.account.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.editor.EditorForm
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class QuickScrollToTopUiTest {
    @get:Rule val compose = createComposeRule()
    private val author = Member(1, "fixture", "离线作者", "", avatarDetailsLoaded = true)
    private fun post(n: Int) = Post(n, n, author, "<p>第 $n 楼正文</p>", "", 0, false, null, null, true)
    private fun button() = compose.onNodeWithTag("quick-scroll-to-top")

    @Composable private fun ListFixture(state: LazyListState, modifier: Modifier = Modifier, enabled: Boolean = true) {
        LazyColumn(modifier.fillMaxSize().quickScrollTarget(state, enabled = enabled).testTag("fixture-list"), state = state) {
            items(80) { index -> Text("列表内容 $index", Modifier.fillMaxWidth().height(64.dp)
                .background(if (index % 2 == 0) Color(0xFFF3E8C8) else Color(0xFFE3EAF0)).padding(16.dp)) }
        }
    }

    @Test fun settingDefaultsOnAndPersistsWithoutTouchingAccountPreferences() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureName = "quick_top_fixture_${System.nanoTime()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(fixtureName, mode)
        }
        try {
            val store = AppSettings(context)
            assertTrue(store.display.value.showScrollToTop)
            var settings by mutableStateOf(store.display.value)
            compose.setContent { KudoTheme(settings) { QuickScrollToTopHost {
                SettingsScreen(settings, { settings = it; store.update(it) }, null, {}, {}, {})
            } } }
            compose.onNodeWithTag("show-scroll-to-top").performScrollTo().assertIsOn().performClick().assertIsOff()
            assertFalse(AppSettings(context).display.value.showScrollToTop)
            compose.onNodeWithTag("show-scroll-to-top").performClick().assertIsOn()
            assertTrue(AppSettings(context).display.value.showScrollToTop)
        } finally { base.deleteSharedPreferences(fixtureName) }
    }

    @Test fun listAppearsAfterScrollReturnsToTheTopAndHonorsLiveSettingChanges() {
        lateinit var scroll: LazyListState
        var enabled by mutableStateOf(true)
        compose.setContent { KudoTheme(DisplaySettings(showScrollToTop = enabled)) { QuickScrollToTopHost {
            scroll = rememberLazyListState()
            ListFixture(scroll)
        } } }
        button().assertDoesNotExist()
        compose.onNodeWithTag("fixture-list").performScrollToIndex(40)
        button().assertDoesNotExist()
        compose.onNodeWithTag("fixture-list").performTouchInput { swipe(center, center + Offset(0f, 120f), 600) }
        button().assertIsDisplayed()
        compose.onNodeWithTag("fixture-list").performTouchInput { swipe(center, center - Offset(0f, 120f), 600) }
        button().assertDoesNotExist()
        compose.onNodeWithTag("fixture-list").performTouchInput { swipe(center, center + Offset(0f, 120f), 600) }
        button().assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1500)
        button().assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        button().assertDoesNotExist()
        compose.runOnIdle { enabled = true }
        button().assertIsDisplayed()
        compose.onNodeWithTag("quick-scroll-to-top-surface", useUnmergedTree = true)
            .performTouchInput { click(Offset(center.x, -4f)) }
        compose.runOnIdle { assertEquals(0, scroll.firstVisibleItemIndex); assertEquals(0, scroll.firstVisibleItemScrollOffset) }
        button().assertDoesNotExist()
    }

    @Test fun shortScrollStaysHiddenAndThresholdFollowsViewportHeight() {
        var height by mutableStateOf(320.dp)
        lateinit var scroll: LazyListState
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            Box(Modifier.fillMaxWidth().height(height)) {
                scroll = rememberLazyListState()
                ListFixture(scroll)
            }
        } } }
        val list = compose.onNodeWithTag("fixture-list")
        list.performScrollToIndex(1)
        button().assertDoesNotExist()
        capture("quick-top-threshold-short.png")
        list.performScrollToIndex(6)
        button().assertDoesNotExist()
        list.performTouchInput { swipe(center, center + Offset(0f, 60f), 600) }
        button().assertIsDisplayed()
        capture("quick-top-threshold-distant.png")
        compose.runOnIdle { height = 560.dp }
        button().assertDoesNotExist()
        list.performScrollToIndex(12)
        list.performTouchInput { swipe(center, center - Offset(0f, 60f), 600) }
        button().assertDoesNotExist()
        list.performTouchInput { swipe(center, center + Offset(0f, 60f), 600) }
        button().assertIsDisplayed()
        list.performScrollToIndex(1)
        button().assertDoesNotExist()
    }

    @Test fun aLongFirstItemCountsItsRealHeightWhenItLeavesTheViewport() {
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            val scroll = rememberLazyListState()
            LazyColumn(Modifier.fillMaxWidth().height(320.dp).quickScrollTarget(scroll).testTag("long-first"), state = scroll) {
                items(40) { Text("内容 $it", Modifier.fillMaxWidth().height(if (it == 0) 960.dp else 64.dp)) }
            }
        } } }
        val list = compose.onNodeWithTag("long-first")
        list.performTouchInput { swipe(center, center - Offset(0f, 60f), 600) }
        button().assertDoesNotExist()
        list.performScrollToIndex(1)
        button().assertDoesNotExist()
        list.performTouchInput { swipe(center, center + Offset(0f, 60f), 600) }
        button().assertIsDisplayed()
        list.performScrollToIndex(2)
        list.performTouchInput { swipe(center, center - Offset(0f, 60f), 600) }
        button().assertDoesNotExist()
        list.performTouchInput { swipe(center, center + Offset(0f, 60f), 600) }
        button().assertIsDisplayed()
        button().performClick()
        button().assertDoesNotExist()
    }

    @Test fun gridThresholdCountsRowsAndPlainScrollAlsoWaitsForAViewport() {
        var grid by mutableStateOf(true)
        var plainScroll: (Int) -> Unit = {}
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            if (grid) {
                val scroll = rememberLazyGridState()
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxWidth().height(320.dp)
                    .quickScrollTarget(scroll).testTag("threshold-grid"), state = scroll) {
                    items(80) { Text("徽章 $it", Modifier.height(64.dp)) }
                }
            } else {
                val scroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                Column(Modifier.fillMaxWidth().height(320.dp).verticalScroll(scroll).quickScrollTarget(scroll).testTag("threshold-plain")) {
                    Text("长资料", Modifier.height(4000.dp))
                }
                SideEffect { plainScroll = { value -> scope.launch { scroll.scrollTo(value) } } }
            }
        } } }
        val list = compose.onNodeWithTag("threshold-grid")
        list.performScrollToIndex(2)
        button().assertDoesNotExist()
        list.performScrollToIndex(16)
        button().assertDoesNotExist()
        list.performTouchInput { swipe(center, center + Offset(0f, 60f), 600) }
        button().assertIsDisplayed().performClick()
        button().assertDoesNotExist()
        compose.runOnIdle { grid = false }
        compose.runOnIdle { plainScroll(60) }
        button().assertDoesNotExist()
        compose.runOnIdle { plainScroll(2000) }
        button().assertDoesNotExist()
        compose.onNodeWithTag("threshold-plain").performTouchInput { swipe(center, center + Offset(0f, 60f), 600) }
        button().assertIsDisplayed().performClick()
        button().assertDoesNotExist()
    }

    @Test fun restoredPositionAndReverseChatKeepTheirReturnDestinations() {
        var reverse by mutableStateOf(false)
        lateinit var scroll: LazyListState
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            key(reverse) {
                scroll = rememberLazyListState(initialFirstVisibleItemIndex = if (reverse) 0 else 40)
                val past = rememberQuickScrollPastViewport(scroll)
                LazyColumn(Modifier.fillMaxWidth().height(320.dp).testTag("restored-reverse")
                    .quickScrollTarget(scrolled = past && (!reverse || scroll.canScrollForward),
                        scrollingUp = if (reverse) scroll.lastScrolledForward else scroll.lastScrolledBackward) {
                        scroll.animateScrollToItem(if (reverse) 79 else 0)
                    }, state = scroll, reverseLayout = reverse) {
                    items(80) { Text("消息 $it", Modifier.height(64.dp).fillMaxWidth()) }
                }
            }
        } } }
        button().assertDoesNotExist()
        compose.onNodeWithTag("restored-reverse").performTouchInput { swipe(center, center + Offset(0f, 120f), 600) }
        button().assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse(scroll.canScrollBackward); reverse = true }
        button().assertDoesNotExist()
        val list = compose.onNodeWithTag("restored-reverse")
        list.performScrollToIndex(1)
        button().assertDoesNotExist()
        list.performScrollToIndex(10)
        list.performTouchInput { swipe(center, center + Offset(0f, 60f), 600) }
        button().assertIsDisplayed()
        list.performTouchInput { swipe(center, center - Offset(0f, 60f), 600) }
        button().assertDoesNotExist()
        list.performTouchInput { swipe(center, center + Offset(0f, 60f), 600) }
        button().assertIsDisplayed().performClick()
        compose.onNodeWithText("消息 79").assertIsDisplayed()
        compose.runOnIdle { assertFalse(scroll.canScrollForward) }
        button().assertDoesNotExist()
    }

    @Test fun pagerOnlyReturnsTheCurrentListAndKeepsTheOtherReadingPosition() {
        val states = mutableListOf<LazyListState>()
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            val pager = rememberPagerState(pageCount = { 2 })
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("fixture-pager")) { index ->
                val scroll = rememberLazyListState()
                SideEffect { if (states.size <= index) states.add(scroll) else states[index] = scroll }
                LazyColumn(Modifier.fillMaxSize().testTag("page-$index")
                    .quickScrollTarget(scroll, enabled = pager.settledPage == index), state = scroll) {
                    items(80) { Text("分页 $index 内容 $it", Modifier.height(64.dp).fillMaxWidth()) }
                }
            }
        } } }
        compose.onNodeWithTag("page-0").performScrollToIndex(40)
        button().assertDoesNotExist()
        compose.onNodeWithTag("page-0").performTouchInput { swipe(center, center + Offset(0f, 120f), 600) }
        button().assertIsDisplayed()
        val savedIndex = states[0].firstVisibleItemIndex
        val savedOffset = states[0].firstVisibleItemScrollOffset
        compose.onNodeWithTag("fixture-pager").performTouchInput { swipeLeft() }
        button().assertDoesNotExist()
        compose.onNodeWithTag("page-1").performScrollToIndex(25)
        button().assertDoesNotExist()
        compose.onNodeWithTag("page-1").performTouchInput { swipe(center, center + Offset(0f, 120f), 600) }
        button().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(savedIndex, states[0].firstVisibleItemIndex)
            assertEquals(savedOffset, states[0].firstVisibleItemScrollOffset)
            assertEquals(0, states[1].firstVisibleItemIndex)
        }
    }

    @Test fun busyActionCannotRepeatAndCoveredContentCannotExposeItsButton() {
        var calls = 0
        var covered by mutableStateOf(false)
        val result = CompletableDeferred<Unit>()
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            CompositionLocalProvider(LocalReadingBlocked provides covered) {
                Box(Modifier.fillMaxSize().quickScrollTarget(scrolled = true, scrollingUp = true) { calls++; result.await() })
            }
        } } }
        button().performClick().assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls); result.complete(Unit) }
        button().assertIsEnabled()
        compose.runOnIdle { covered = true }
        button().assertDoesNotExist()
        compose.runOnIdle { covered = false }
        button().assertIsDisplayed()
    }

    @Test fun publicProfileReturnsBothTheListAndItsCollapsedHeader() {
        val profile = PublicProfile(author, 3, 5, "", "2024-01-01", "", "", "成员", false)
        val summary = ProfileSummary(emptyMap(), (1..40).map { ProfileEntry("topic-$it", it, null, "热门话题 $it") },
            emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())
        val state = PublicProfileState(profile, loading = false, cardLoading = false, summary = summary, summaryLoading = false)
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            PublicProfilePage(state, null, {}, {}, {}, {}, {}, {})
        } } }
        val expandedHeight = compose.onNodeWithTag("public-profile-header-viewport").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("profile-list-SUMMARY").performTouchInput {
            val start = Offset(centerX, height * .8f)
            swipe(start, start - Offset(0f, 90f), 600)
        }
        button().assertDoesNotExist()
        repeat(2) {
            compose.onNodeWithTag("profile-list-SUMMARY").performTouchInput {
                swipe(Offset(centerX, height * .85f), Offset(centerX, height * .35f), 600)
            }
        }
        compose.onNodeWithTag("profile-list-SUMMARY").performScrollToIndex(25)
        button().assertDoesNotExist()
        compose.onNodeWithTag("profile-list-SUMMARY").performTouchInput {
            val start = Offset(centerX, height * .8f)
            swipe(start, start + Offset(0f, 120f), 600)
        }
        button().assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("public-profile-header-viewport").fetchSemanticsNode().boundsInRoot.height < expandedHeight)
        button().performClick()
        assertEquals(expandedHeight, compose.onNodeWithTag("public-profile-header-viewport").fetchSemanticsNode().boundsInRoot.height, 3f)
        button().assertDoesNotExist()
    }

    @Test fun accessoryStaysAvailableWhenTheReturnDirectionIsHidden() {
        var opened = 0
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            Box(Modifier.fillMaxSize().quickScrollTarget(scrolled = true, scrollingUp = false) {}
                .quickScrollAccessory(true, Icons.Rounded.Menu, "帖子目录") { opened++ })
        } } }
        button().assertDoesNotExist()
        compose.onNodeWithTag("quick-scroll-accessory").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun distantTopicReturnsToTheTitleAndFirstFloorAndKeepsTheWindowOnFailure() {
        val original = post(1)
        val remote = TopicDetail(5, "回顶必须显示话题标题", 1, (500..539).map(::post), (1..600).toList(), false,
            pinnedOriginal = original, highestPostNumber = 600)
        var state by mutableStateOf(TopicState(remote, false, targetNumber = 500))
        var requests = 0
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            TopicScreen(state, GlassMode.LIQUID, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, jumpTo = {
                assertEquals(1, it); requests++
                state = state.copy(jumping = true, jumpError = null)
            })
        } } }
        compose.onNodeWithTag("post-500").assertIsDisplayed()
        button().assertDoesNotExist()
        compose.onNodeWithTag("topic-posts").performTouchInput { swipe(center, center + Offset(0f, 120f), 600) }
        button().performClick()
        compose.runOnIdle { state = state.copy(jumping = false, jumpError = "顶部加载失败，请重试") }
        compose.onNodeWithText("顶部加载失败，请重试").assertIsDisplayed()
        compose.onNodeWithTag("post-500").assertIsDisplayed()
        button().performClick()
        compose.runOnIdle { state = state.copy(detail = remote.copy(posts = (1..40).map(::post)), jumping = false,
            jumpError = null, targetNumber = 1, targetRevision = 1) }
        compose.onNodeWithText(remote.title).assertIsDisplayed()
        compose.onNodeWithTag("post-1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, requests) }
        button().assertDoesNotExist()
    }

    @Test fun anExternalReplyPanelHidesTheUnderlyingTopicReturnButton() {
        var replyShowing by mutableStateOf(false)
        val posts = (1..40).map(::post)
        val state = TopicState(TopicDetail(5, "回复面板覆盖", 1, posts, posts.map(Post::id), false), false)
        compose.setContent { KudoTheme(DisplaySettings()) { QuickScrollToTopHost {
            TopicScreen(state, GlassMode.LIQUID, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, readingEnabled = !replyShowing)
        } } }
        compose.onNodeWithTag("topic-posts").performScrollToIndex(8)
        button().assertDoesNotExist()
        compose.onNodeWithTag("topic-posts").performTouchInput { swipe(center, center + Offset(0f, 60f), 600) }
        button().assertIsDisplayed()
        capture("ui-polish-topic-actions.png")
        compose.runOnIdle { replyShowing = true }
        button().assertDoesNotExist()
    }

    @Test fun threeMaterialsStayAboveTheBottomBarInDarkLargeText() {
        var mode by mutableStateOf(GlassMode.LIQUID)
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f, glass = mode)) {
            QuickScrollToTopHost {
                CompositionLocalProvider(LocalQuickScrollBottomPadding provides 88.dp) {
                    Box(Modifier.fillMaxSize()) {
                        ListFixture(rememberLazyListState())
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
                            .background(Color.DarkGray).testTag("fixture-bottom-bar"))
                    }
                }
            }
        } }
        GlassMode.entries.forEach { material ->
            compose.runOnIdle { mode = material }
            compose.onNodeWithTag("fixture-list").performScrollToIndex(30)
            compose.onNodeWithTag("fixture-list").performTouchInput { swipe(center, center - Offset(0f, 60f), 600) }
            button().assertDoesNotExist()
            compose.onNodeWithTag("fixture-list").performTouchInput { swipe(center, center + Offset(0f, 120f), 600) }
            val bounds = button().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.bottom < compose.onNodeWithTag("fixture-bottom-bar").fetchSemanticsNode().boundsInRoot.top)
            capture("quick-top-material-${material.name}.png")
            button().performClick()
            button().assertDoesNotExist()
        }
    }

    @Test fun replyCountIsCompactAndTheMinimumStillControlsPublishing() {
        var draft by mutableStateOf(Draft())
        compose.setContent { KudoTheme(DisplaySettings()) {
            EditorForm(draft, emptyList(), { draft = it }, {}, {}, {}, reply = true, replyRules = ReplyRules(6, 100))
        } }
        compose.onNodeWithTag("reply-length").assertTextEquals("0 / 6 字")
        compose.onNodeWithText("发布").assertIsNotEnabled()
        compose.runOnIdle { draft = Draft(raw = "12345") }
        compose.onNodeWithTag("reply-length").assertTextEquals("5 / 6 字")
        compose.onNodeWithText("发布").assertIsNotEnabled()
        compose.runOnIdle { draft = Draft(raw = "123456") }
        compose.onNodeWithTag("reply-length").assertTextEquals("6 字")
        compose.onNodeWithText("发布").assertIsEnabled()
        compose.runOnIdle { draft = Draft(raw = "1234567890") }
        compose.onNodeWithTag("reply-length").assertTextEquals("10 字")
        compose.onNodeWithText("回复至少", substring = true).assertDoesNotExist()
        compose.onNodeWithText("草稿已自动保存", substring = true).assertDoesNotExist()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
