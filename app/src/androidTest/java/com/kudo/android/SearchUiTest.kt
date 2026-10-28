package com.kudo.android

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.feed.*
import com.kudo.android.ui.components.QuickScrollToTopHost
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.cancel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class SearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = CopyOnWriteArrayList<Request>()
    private var vm: SearchViewModel? = null
    private var accountId by mutableStateOf<Int?>(7)
    private val openedTopics = mutableListOf<Pair<Int, Int?>>()
    private val openedUsers = mutableListOf<String>()
    private val openedCategories = mutableListOf<Int>()
    private val openedTags = mutableListOf<TopicTag>()
    private var backCount = 0
    private var manyResults = false
    private val recentTerms = CopyOnWriteArrayList(listOf("开发", "安卓"))
    private val failClear = AtomicBoolean(false)
    private val preload = JSONObject().put("siteSettings", JSONObject().put("tagging_enabled", true).put("solved_enabled", true)
        .put("enable_category_experts", true).put("show_category_expert_advanced_search_filters", true))
        .put("currentUser", JSONObject().put("id", 7).put("can_assign_globally", true))
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); requests += request
        var code = 200
        val body = when (request.url.encodedPath) {
            "/session/csrf" -> """{"csrf":"test-token"}"""
            "/u/recent-searches.json" -> if (request.method == "DELETE") {
                if (failClear.getAndSet(false)) { code = 503; """{"errors":["清空失败，请稍后重试"]}""" }
                else { recentTerms.clear(); """{"success":true}""" }
            } else JSONObject().put("recent_searches", org.json.JSONArray(recentTerms)).toString()
            "/site.json" -> """{"categories":[{"id":4,"name":"开发调优","slug":"dev","description":"讨论技术"}]}"""
            "/tags/filter/search" -> """{"results":[{"text":"开发","slug":"123-tag"}]}"""
            "/u/search/users.json" -> """{"users":[{"id":8,"username":"developer","name":"开发者","avatar_template":""}]}"""
            "/latest" -> "<html><div data-preloaded='$preload'></div></html>"
            else -> if (manyResults) """{"topics":[${(1..40).joinToString(",") { """{"id":$it,"title":"搜索话题$it"}""" }}],"posts":[${(1..40).joinToString(",") { """{"id":$it,"topic_id":$it,"post_number":1}""" }}],"grouped_search_result":{"more_full_page_results":false}}"""
                else """{"topics":[{"id":3,"title":"开发工具介绍","posts_count":10,"views":100}],"posts":[{"id":32,"topic_id":3,"post_number":9,"username":"author","user_id":8,"blurb":"命中第九楼的摘要"},{"id":31,"topic_id":3,"post_number":2,"username":"other","user_id":9,"blurb":"另一个命中摘要"}],"grouped_search_result":{"more_full_page_results":false}}"""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Unavailable")
            .body(body.toResponseBody((if (request.url.encodedPath == "/latest") "text/html" else "application/json").toMediaType())).build()
    }.build()

    @Before fun configureWindow() { compose.runOnUiThread {
        compose.activity.enableEdgeToEdge()
        compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    } }

    @After fun dispose() {
        compose.runOnIdle { vm?.viewModelScope?.cancel() }
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    private fun show(settings: DisplaySettings = DisplaySettings(), short: Boolean = false) {
        compose.runOnIdle { vm = SearchViewModel(CommunityRepository(DiscourseClient(object : SessionCookies {
            override fun get(url: String) = ""
            override fun set(url: String, cookie: String) {}
        }, http = http)), accountId) }
        compose.setContent { KudoTheme(settings) {
            val state by vm!!.state.collectAsState()
            LaunchedEffect(accountId) { vm!!.useAccount(accountId) }
            QuickScrollToTopHost {
                Box(if (short) Modifier.fillMaxWidth().height(380.dp) else Modifier.fillMaxSize()) {
                    SearchScreen(state.forAccount(accountId), vm!!::search, { backCount++ }, { id, number -> openedTopics += id to number }, openedUsers::add, {},
                        listStyle = TopicListStyle.TITLE, openCategory = openedCategories::add, openTag = openedTags::add,
                        changeKind = vm!!::changeKind, changeSort = vm!!::changeSort, clear = vm!!::clear, refreshHistory = vm!!::refreshHistory,
                        loadMetadata = vm!!::loadMetadata, suggestTags = vm!!::suggestTags, applyFilters = vm!!::applyFilters, clearHistory = vm!!::clearHistory,
                        initialScrollIndex = vm!!.scrollIndex, initialScrollOffset = vm!!.scrollOffset, saveScrollPosition = vm!!::saveScrollPosition)
                }
            }
        } }
        compose.waitUntil(5000) { !vm!!.state.value.historyLoading }
    }

    private fun search() {
        compose.onNodeWithTag("search-query").performTextReplacement("开发")
        compose.onNodeWithTag("search-submit").performClick()
        compose.waitUntil(5000) { vm!!.state.value.searched && !vm!!.state.value.loading }
    }

    @Test fun resultScrollingHidesBothTopRowsPinsSortingAndReverseRevealsThem() {
        manyResults = true
        show(); search()
        val expanded = compose.onNodeWithTag("search-header").fetchSemanticsNode().boundsInRoot
        val initialSort = compose.onNodeWithTag("search-sort-RELEVANCE").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("search-results").performScrollToIndex(20)
        scrollResults(-500f)
        compose.onNodeWithTag("search-input").assertIsNotDisplayed()
        compose.onNodeWithText("帖子").assertIsNotDisplayed()
        val pinnedSort = compose.onNodeWithTag("search-sort-RELEVANCE").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertTrue(pinnedSort < initialSort - 100f)
        assertTrue("状态栏区域不能一起移走", pinnedSort > expanded.top)
        capture("search-collapse-pinned.png")
        scrollResults(-400f)
        assertEquals(pinnedSort, compose.onNodeWithTag("search-sort-RELEVANCE").fetchSemanticsNode().boundsInRoot.top, 1f)
        scrollResults(400f)
        compose.onNodeWithTag("search-input").assertIsDisplayed()
        compose.onNodeWithText("帖子").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("search-query").assertTextEquals("开发")
        assertEquals(initialSort, compose.onNodeWithTag("search-sort-RELEVANCE").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onNodeWithTag("search-hit-1").assertDoesNotExist()
        capture("search-collapse-revealed.png")
    }

    @Test fun quickReturnExpandsTheCollapsedSearchHeaderAndKeepsTheQuery() {
        manyResults = true
        show(); search()
        compose.onNodeWithTag("search-results").performScrollToIndex(20)
        scrollResults(-500f)
        compose.onNodeWithTag("search-input").assertIsNotDisplayed()
        scrollResults(400f)
        compose.onNodeWithTag("quick-scroll-to-top").assertIsDisplayed().performClick()
        compose.onNodeWithTag("search-input").assertIsDisplayed()
        compose.onNodeWithText("帖子").assertIsDisplayed()
        compose.onNodeWithTag("search-hit-1").assertIsDisplayed()
        compose.onNodeWithTag("search-query").assertTextEquals("开发")
        compose.onNodeWithTag("quick-scroll-to-top").assertDoesNotExist()
    }

    @Test fun openingFiltersExpandsTheHeaderAndPanelScrollingCannotCollapseIt() {
        manyResults = true
        show(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)); search()
        compose.onNodeWithTag("search-results").performScrollToIndex(20)
        scrollResults(-600f)
        compose.onNodeWithTag("search-input").assertIsNotDisplayed()
        val viewport = compose.onNodeWithTag("search-results").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("search-filter-toggle").performClick()
        compose.waitUntil(5000) { vm!!.state.value.metadataLoaded }
        compose.onNodeWithTag("search-input").assertIsDisplayed()
        val expandedHeight = compose.onNodeWithTag("search-header").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("search-assigned").performScrollTo()
        compose.onNodeWithText("有专家回应").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("search-filters-panel").performTouchInput {
            swipe(Offset(centerX, height * .65f), Offset(centerX, height * .3f), 800)
        }
        assertEquals(expandedHeight, compose.onNodeWithTag("search-header").fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag("search-filter-apply").assertIsDisplayed()
        compose.onNodeWithTag("quick-scroll-to-top").assertDoesNotExist()
        assertEquals(viewport, compose.onNodeWithTag("search-results").fetchSemanticsNode().boundsInRoot)
        capture("search-collapse-filter-dark.png")
        compose.onNodeWithContentDescription("收起高级筛选").performClick()
        scrollResults(-600f)
        compose.onNodeWithTag("search-input").assertIsNotDisplayed()
        compose.onNodeWithTag("search-sort-LIKES").performClick()
        compose.waitUntil(5000) { !vm!!.state.value.loading && vm!!.state.value.sort == SearchSort.LIKES }
        compose.onNodeWithTag("search-input").assertIsDisplayed()
        compose.onNodeWithTag("search-sort-LIKES").assertIsSelected()
    }

    private fun scrollResults(distance: Float) {
        compose.onNodeWithTag("search-results").performTouchInput {
            val start = Offset(centerX, height * if (distance < 0f) .8f else .45f)
            swipe(start, start + Offset(0f, distance), 800)
        }
    }

    @Test fun restoredSearchKeepsItsCollapsedHeaderAndReadingPositionUntilANewQuery() {
        val hits = (1..40).map { number -> SearchHit(number, 1,
            Topic(number, "恢复位置话题 $number", "", null, 1, emptyList(), 0, 0, 0, "", "")) }
        var state by mutableStateOf(SearchState(query = "开发", hits = SearchResults(posts = hits), searched = true))
        var savedIndex = 0
        var savedOffset = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KudoTheme(DisplaySettings()) {
            SearchScreen(state, { _, _ -> }, {}, { _, _ -> }, {}, {}, listStyle = TopicListStyle.TITLE,
                initialScrollIndex = savedIndex, initialScrollOffset = savedOffset,
                saveScrollPosition = { index, offset -> savedIndex = index; savedOffset = offset })
        } }
        compose.onNodeWithTag("search-results").performScrollToIndex(20)
        scrollResults(-500f)
        compose.onNodeWithTag("search-input").assertIsNotDisplayed()
        val headerHeight = compose.onNodeWithTag("search-header").fetchSemanticsNode().boundsInRoot.height
        val position = compose.onNodeWithTag("search-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("search-input").assertIsNotDisplayed()
        assertEquals(headerHeight, compose.onNodeWithTag("search-header").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(position, compose.onNodeWithTag("search-results").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        compose.runOnIdle {
            savedIndex = 0; savedOffset = 0
            state = state.copy(query = "新查询", scrollRevision = state.scrollRevision + 1)
        }
        compose.onNodeWithTag("search-input").assertIsDisplayed()
        compose.onNodeWithTag("search-query").assertTextEquals("新查询")
        compose.onNodeWithTag("search-hit-1").assertIsDisplayed()
    }

    @Test fun narrowLargeTextUserResultsCollapseAndChangingKindRevealsControls() {
        val users = (1..40).map { Member(it, "user$it", "搜索用户 $it", "") }
        var state by mutableStateOf(SearchState(query = "查人", kind = SearchKind.USERS,
            hits = SearchResults(users = users), searched = true))
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f)) {
            Box(Modifier.width(280.dp).height(500.dp)) {
                SearchScreen(state, { _, _ -> }, {}, { _, _ -> }, {}, {}, changeKind = { state = state.copy(kind = it) })
            }
        } }
        val expanded = compose.onNodeWithTag("search-header").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("search-results").performScrollToIndex(20)
        scrollResults(-500f)
        compose.onNodeWithTag("search-input").assertIsNotDisplayed()
        compose.onNodeWithText("用户").assertIsNotDisplayed()
        assertTrue(compose.onNodeWithTag("search-header").fetchSemanticsNode().boundsInRoot.height < expanded - 100f)
        capture("search-collapse-users-dark-narrow.png")
        compose.onNodeWithTag("search-pager").performTouchInput { swipeRight(durationMillis = 600) }
        compose.onNodeWithTag("search-input").assertIsDisplayed()
        compose.onNodeWithText("类别与标签").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText("帖子").performClick()
        compose.onNodeWithTag("search-query").performClick().assertIsFocused()
        scrollResults(-300f)
        compose.onNodeWithTag("search-input").assertIsDisplayed()
    }

    @Test fun searchAndFilterUseSiblingMaterialsWithSearchActionInsideTheOval() {
        var settings by mutableStateOf(DisplaySettings(theme = ThemeMode.LIGHT, fontScale = 1.3f))
        compose.setContent { KudoTheme(settings) {
            Box(Modifier.width(280.dp).fillMaxHeight()) {
                SearchScreen(SearchState(), { _, _ -> }, {}, { _, _ -> }, {}, {})
            }
        } }
        compose.onNodeWithTag("search-submit").assert(hasAnyAncestor(hasTestTag("search-input"))).assertIsNotEnabled()
        compose.onNodeWithTag("search-query").performTextReplacement("测试")
        compose.onNodeWithTag("search-submit").assertIsEnabled().performClick()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (glass in GlassMode.entries) {
            compose.runOnIdle { settings = settings.copy(theme = theme, glass = glass) }
            compose.onNodeWithTag("search-header").assert(!hasAnyAncestor(hasTestTag("search-results")))
            val before = compose.onNodeWithTag("search-results").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("search-filter-toggle").performClick()
            compose.onNodeWithTag("search-filters-panel").assertIsDisplayed().assert(!hasAnyAncestor(hasTestTag("search-results")))
            assertEquals(before, compose.onNodeWithTag("search-results").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("search-filter-apply").assertIsDisplayed()
            capture("search-material-${theme.name}-${glass.name}.png")
            compose.onNodeWithContentDescription("收起高级筛选").performClick()
        }
    }

    @Test fun recentSearchesComeFromServerAndLogoutRemovesThem() {
        show()
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
        compose.onNodeWithText("安卓").performClick()
        compose.waitUntil(5000) { vm!!.state.value.query == "安卓" && !vm!!.state.value.loading }
        compose.onNodeWithContentDescription("清空搜索").performClick()
        compose.runOnIdle { accountId = null }
        compose.waitUntil(5000) { vm!!.state.value.accountId == null }
        compose.onNodeWithText("最近搜索").assertDoesNotExist()
        compose.onNodeWithTag("search-sort-READ").assertDoesNotExist()
        assertTrue(vm!!.state.value.recent.isEmpty())
    }

    @Test fun guestDoesNotRequestServerHistory() {
        accountId = null; show(); search()
        assertTrue(requests.none { it.url.encodedPath == "/u/recent-searches.json" })
        compose.onNodeWithText("最近搜索").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空最近搜索").assertDoesNotExist()
    }

    @Test fun historyClearUsesLiquidConfirmationCanCancelAndHidesTheWholeSection() {
        show()
        compose.onNodeWithContentDescription("清空最近搜索").performClick()
        compose.onNodeWithTag("liquid-dialog").assertIsDisplayed()
        compose.onNodeWithText("清空最近搜索？").assertIsDisplayed()
        capture("search-clear-history-dialog-light.png")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
        compose.onNodeWithText("安卓").assertIsDisplayed()
        assertTrue(requests.none { it.method == "DELETE" })
        compose.onNodeWithContentDescription("清空最近搜索").performClick()
        compose.onNodeWithTag("search-confirm-clear-history").performClick()
        compose.waitUntil(5000) { !vm!!.state.value.historyClearing && vm!!.state.value.recent.isEmpty() }
        compose.onNodeWithText("最近搜索").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空最近搜索").assertDoesNotExist()
        compose.onNodeWithText("暂无最近搜索").assertDoesNotExist()
        compose.onNodeWithTag("liquid-dialog").assertDoesNotExist()
        assertEquals(1, requests.count { it.method == "DELETE" && it.url.encodedPath == "/u/recent-searches.json" })
        compose.runOnIdle { vm!!.refreshHistory() }
        compose.waitUntil(5000) { !vm!!.state.value.historyLoading }
        compose.onNodeWithText("最近搜索").assertDoesNotExist()
        capture("search-history-cleared.png")
    }

    @Test fun historyClearFailureKeepsEntriesAndShowsErrorInTheDarkLiquidDialog() {
        failClear.set(true)
        show(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f))
        compose.onNodeWithContentDescription("清空最近搜索").performClick()
        compose.onNodeWithTag("search-confirm-clear-history").performClick()
        compose.waitUntil(5000) { vm!!.state.value.historyClearError != null }
        compose.onNodeWithTag("liquid-dialog").assertIsDisplayed()
        compose.onNodeWithText("清空失败，请稍后重试").assertIsDisplayed()
        assertEquals(listOf("开发", "安卓"), vm!!.state.value.recent)
        assertEquals(1, requests.count { it.method == "DELETE" })
        capture("search-clear-history-dialog-dark-error.png")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("最近搜索").assertIsDisplayed()
        compose.onNodeWithText("安卓").assertIsDisplayed()
    }

    @Test fun emptyServerHistoryDoesNotShowTheHistorySection() {
        recentTerms.clear(); show()
        compose.onNodeWithText("最近搜索").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空最近搜索").assertDoesNotExist()
        compose.onNodeWithText("暂无最近搜索").assertDoesNotExist()
    }

    @Test fun matchingFloorsKeepTheirOwnSummaryAndNavigationTarget() {
        show(); search()
        compose.onNodeWithText("命中第九楼的摘要").assertIsDisplayed()
        compose.onNodeWithTag("search-query").assertIsNotFocused()
        compose.onNodeWithText("命中第 9 楼").performClick()
        assertEquals(listOf(3 to 9), openedTopics)
        capture("search-post-results.png")
    }

    @Test fun returningFromTopicRestoresTheSearchResultPosition() {
        val author = Member(1, "author", "作者", "")
        val hits = (1..40).map { number ->
            SearchHit(number, number, Topic(number, "搜索主题 $number", "搜索摘要 $number", author, 1, emptyList(), 0, 0, 0, "", ""))
        }
        val state = SearchState(accountId = 7, query = "测试", hits = SearchResults(posts = hits), searched = true)
        var showingSearch by mutableStateOf(true)
        var savedIndex = 0
        var savedOffset = 0
        compose.setContent {
            KudoTheme(DisplaySettings()) {
                if (showingSearch) {
                    SearchScreen(state, { _, _ -> }, {}, { _, _ -> showingSearch = false }, {}, {}, listStyle = TopicListStyle.TITLE,
                        initialScrollIndex = savedIndex, initialScrollOffset = savedOffset,
                        saveScrollPosition = { index, offset -> savedIndex = index; savedOffset = offset })
                } else {
                    androidx.compose.foundation.text.BasicText("主题详情", Modifier.testTag("topic-detail"))
                }
            }
        }
        compose.onNodeWithTag("search-results").performScrollToNode(hasTestTag("search-hit-20"))
        compose.onNodeWithTag("search-hit-20").assertIsDisplayed()
        compose.runOnIdle { showingSearch = false }
        compose.onNodeWithTag("topic-detail").assertIsDisplayed()
        compose.runOnIdle { showingSearch = true }
        compose.onNodeWithTag("search-hit-20").assertIsDisplayed()
    }

    @Test fun threeTypesUseNativeCategoryTagAndUserCallbacks() {
        show(); search()
        compose.onNodeWithText("类别与标签").performClick()
        compose.waitUntil(5000) { vm!!.state.value.kind == SearchKind.CATEGORIES_TAGS && !vm!!.state.value.loading }
        compose.onAllNodesWithText("开发调优", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("讨论技术").performClick()
        compose.onNodeWithText("查看此标签下的话题").performClick()
        assertEquals(listOf(4), openedCategories)
        assertEquals("123-tag", openedTags.single().slug)
        compose.onNodeWithText("类别与标签").assertIsSelected()
        capture("search-category-tag-results.png")
        compose.onNodeWithText("用户").performClick()
        compose.waitUntil(5000) { vm!!.state.value.kind == SearchKind.USERS && !vm!!.state.value.loading }
        compose.onNodeWithText("@developer").performClick()
        assertEquals(listOf("developer"), openedUsers)
        compose.onNodeWithText("用户").assertIsSelected()
        compose.onNodeWithContentDescription("高级筛选").assertDoesNotExist()
        capture("search-user-results.png")
    }

    @Test fun swipingKindsLoadsOnlyTheSettledKindAndReusesSuccessfulResults() {
        show(); search()
        compose.onNodeWithTag("search-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.waitUntil(5000) { vm!!.state.value.kind == SearchKind.CATEGORIES_TAGS && !vm!!.state.value.loading }
        compose.onNodeWithText("类别与标签").assertIsSelected()
        compose.onNodeWithTag("search-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.waitUntil(5000) { vm!!.state.value.kind == SearchKind.USERS && !vm!!.state.value.loading }
        compose.onNodeWithText("用户").assertIsSelected()
        val count = requests.size
        compose.onNodeWithText("帖子").performClick().assertIsSelected()
        compose.onNodeWithText("命中第 9 楼").assertIsDisplayed()
        compose.onNodeWithText("用户").performClick().assertIsSelected()
        compose.onNodeWithText("@developer").assertIsDisplayed()
        assertEquals("切回已成功的类型不重复搜索", count, requests.size)
    }

    @Test fun swipingAcrossOtherKindsRestoresThePostResultPosition() {
        manyResults = true
        show(); search()
        compose.onNodeWithTag("search-results").performScrollToNode(hasTestTag("search-hit-20"))
        compose.onNodeWithTag("search-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.waitUntil(5000) { vm!!.state.value.kind == SearchKind.CATEGORIES_TAGS && !vm!!.state.value.loading }
        compose.onNodeWithTag("search-pager").performTouchInput { swipeLeft(durationMillis = 600) }
        compose.waitUntil(5000) { vm!!.state.value.kind == SearchKind.USERS && !vm!!.state.value.loading }
        compose.onNodeWithText("帖子").performClick().assertIsSelected()
        compose.onNodeWithTag("search-hit-20").assertIsDisplayed()
    }

    @Test fun sortingAndDropdownFiltersAreSubmittedToServer() {
        show(); search()
        compose.onNodeWithTag("search-sort-LIKES").performClick()
        compose.waitUntil(5000) { requests.any { it.url.queryParameter("q") == "开发 order:likes" } && !vm!!.state.value.loading }
        compose.onNodeWithContentDescription("高级筛选").performClick()
        compose.waitUntil(5000) { vm!!.state.value.metadataLoaded }
        compose.onNodeWithTag("search-category").performClick()
        compose.onNodeWithText("开发调优").performClick()
        compose.onNodeWithTag("search-author").performScrollTo().performTextReplacement("neo")
        compose.onNodeWithTag("search-filter-apply").performClick()
        compose.waitUntil(5000) { requests.any { it.url.queryParameter("q") == "开发 category:4 @neo order:likes" } && !vm!!.state.value.loading }
        compose.onNodeWithTag("search-filters-panel").assertDoesNotExist()
        compose.onNodeWithTag("search-sort-LIKES").assertIsSelected()
    }

    @Test fun darkLargeTextFiltersScrollWithFixedActionsAndReset() {
        show(DisplaySettings(theme = ThemeMode.DARK, fontScale = 1.3f))
        compose.onNodeWithContentDescription("高级筛选").performClick()
        compose.waitUntil(5000) { vm!!.state.value.metadataLoaded }
        compose.onNodeWithTag("search-assigned").performScrollTo().performTextReplacement("staff")
        compose.onNodeWithText("有专家回应").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("search-filter-apply").assertIsDisplayed()
        capture("search-filters-dark-large.png")
        compose.onNodeWithTag("search-filter-reset").performClick()
        compose.onNodeWithTag("search-assigned").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun shortWindowKeepsDropdownApplyButtonAccessible() {
        show(short = true)
        compose.onNodeWithContentDescription("高级筛选").performClick()
        compose.onNodeWithTag("search-filter-apply").assertIsDisplayed()
        compose.onNodeWithTag("search-min-posts").performScrollTo().performTextReplacement("20")
        compose.onNodeWithTag("search-min-posts").assertTextEquals("20")
        compose.onNodeWithTag("search-max-posts").performScrollTo().performTextReplacement("2")
        compose.onNodeWithTag("search-max-posts").assertTextEquals("2")
        compose.runOnUiThread { WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(5000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom == 0 }
        compose.waitUntil(5000) { compose.onNodeWithTag("search-filter-apply").isDisplayed() }
        compose.onNodeWithTag("search-filter-apply").assertIsDisplayed()
        compose.onNodeWithTag("search-filter-apply").performClick()
        compose.onNodeWithText("帖子数量的最小值不能大于最大值").performScrollTo().assertIsDisplayed()
        assertFalse(vm!!.state.value.searched)
        capture("search-filters-short-window.png")
    }

    @Test fun backClosesDropdownBeforeLeavingSearch() {
        show()
        compose.onNodeWithContentDescription("高级筛选").performClick()
        compose.onNodeWithTag("search-filters-panel").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("search-filters-panel").assertDoesNotExist()
        assertEquals(0, backCount)
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
