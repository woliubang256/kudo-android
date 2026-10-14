package com.kudo.android

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.feed.*
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
    private val preload = JSONObject().put("siteSettings", JSONObject().put("tagging_enabled", true).put("solved_enabled", true)
        .put("enable_category_experts", true).put("show_category_expert_advanced_search_filters", true))
        .put("currentUser", JSONObject().put("id", 7).put("can_assign_globally", true))
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); requests += request
        val body = when (request.url.encodedPath) {
            "/u/recent-searches.json" -> """{"recent_searches":["开发","安卓"]}"""
            "/site.json" -> """{"categories":[{"id":4,"name":"开发调优","slug":"dev","description":"讨论技术"}]}"""
            "/tags/filter/search" -> """{"results":[{"text":"开发","slug":"123-tag"}]}"""
            "/u/search/users.json" -> """{"users":[{"id":8,"username":"developer","name":"开发者","avatar_template":""}]}"""
            "/latest" -> "<html><div data-preloaded='$preload'></div></html>"
            else -> """{"topics":[{"id":3,"title":"开发工具介绍","posts_count":10,"views":100}],"posts":[{"id":32,"topic_id":3,"post_number":9,"username":"author","user_id":8,"blurb":"命中第九楼的摘要"},{"id":31,"topic_id":3,"post_number":2,"username":"other","user_id":9,"blurb":"另一个命中摘要"}],"grouped_search_result":{"more_full_page_results":false}}"""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
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
            Box(if (short) Modifier.fillMaxWidth().height(380.dp) else Modifier.fillMaxSize()) {
                SearchScreen(state.forAccount(accountId), vm!!::search, { backCount++ }, { id, number -> openedTopics += id to number }, openedUsers::add, {},
                    listStyle = TopicListStyle.TITLE, openCategory = openedCategories::add, openTag = openedTags::add,
                    changeKind = vm!!::changeKind, changeSort = vm!!::changeSort, clear = vm!!::clear, refreshHistory = vm!!::refreshHistory,
                    loadMetadata = vm!!::loadMetadata, suggestTags = vm!!::suggestTags, applyFilters = vm!!::applyFilters)
            }
        } }
        compose.waitUntil(5000) { !vm!!.state.value.historyLoading }
    }

    private fun search() {
        compose.onNodeWithTag("search-query").performTextReplacement("开发")
        compose.onNodeWithTag("search-submit").performClick()
        compose.waitUntil(5000) { vm!!.state.value.searched && !vm!!.state.value.loading }
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
    }

    @Test fun matchingFloorsKeepTheirOwnSummaryAndNavigationTarget() {
        show(); search()
        compose.onNodeWithText("命中第九楼的摘要").assertIsDisplayed()
        compose.onNodeWithTag("search-query").assertIsNotFocused()
        compose.onNodeWithText("命中第 9 楼").performClick()
        assertEquals(listOf(3 to 9), openedTopics)
        capture("search-post-results.png")
    }

    @Test fun threeTypesUseNativeCategoryTagAndUserCallbacks() {
        show(); search()
        compose.onNodeWithTag("search-kind-CATEGORIES_TAGS").performClick()
        compose.waitUntil(5000) { vm!!.state.value.kind == SearchKind.CATEGORIES_TAGS && !vm!!.state.value.loading }
        compose.onAllNodesWithText("开发调优", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("讨论技术").performClick()
        compose.onNodeWithText("查看此标签下的话题").performClick()
        assertEquals(listOf(4), openedCategories)
        assertEquals("123-tag", openedTags.single().slug)
        capture("search-category-tag-results.png")
        compose.onNodeWithTag("search-kind-USERS").performClick()
        compose.waitUntil(5000) { vm!!.state.value.kind == SearchKind.USERS && !vm!!.state.value.loading }
        compose.onNodeWithText("@developer").performClick()
        assertEquals(listOf("developer"), openedUsers)
        compose.onNodeWithContentDescription("高级筛选").assertDoesNotExist()
        capture("search-user-results.png")
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
        compose.onNodeWithTag("search-max-posts").performScrollTo().performTextReplacement("2")
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
