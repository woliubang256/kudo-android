package com.kudo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.apps.*
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in read-only probe through the installed app's actual session and network route. */
class AppSquareLiveProbeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun signedInFavoritesUseTheOfficialSessionAndCanReturnToLatest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appSquareLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as KudoApplication).container.repository
        val signedIn = runBlocking { withTimeout(45000) { repository.currentUser() != null } }
        val report = File(context.getExternalFilesDir(null), "apps-favorites-live-result.txt")
        report.writeText("signedIn=$signedIn", Charsets.UTF_8)
        assumeTrue("需要手机上已有登录会话", signedIn)
        lateinit var vm: AppSquareViewModel
        compose.setContent { KudoTheme(DisplaySettings()) {
            vm = androidx.lifecycle.viewmodel.compose.viewModel {
                AppSquareViewModel(load = repository::miniApps)
            }
            val state by vm.state.collectAsStateWithLifecycle()
            AppSquareScreen(state, vm::search, vm::retry, vm::more, {}, {}, {},
                sort = vm::sort, loggedIn = true)
        } }
        fun awaitResults() {
            compose.waitUntil(45000) { !vm.state.value.loading }
            assertNull(vm.state.value.error)
        }
        awaitResults()
        compose.onNodeWithContentDescription("筛选应用").performClick()
        compose.onNodeWithText("已收藏").performClick()
        awaitResults()
        assertEquals(MiniAppSort.FAVORITES, vm.state.value.sort)
        assertEquals(0, vm.state.value.page)
        report.appendText("\nfavoritesLoaded=true, count=${vm.state.value.apps.size}", Charsets.UTF_8)
        compose.onNodeWithContentDescription("搜索应用").performClick()
        compose.onNodeWithTag("apps-search-field").performTextInput("2048")
        awaitResults()
        assertEquals(MiniAppSort.FAVORITES, vm.state.value.sort)
        compose.onNodeWithTag("apps-back").performClick()
        compose.onNodeWithContentDescription("筛选应用").performClick()
        compose.onNodeWithText("最新上线").performClick()
        awaitResults()
        assertEquals(MiniAppSort.LATEST, vm.state.value.sort)
        assertEquals("2048", vm.state.value.query)
        assertTrue(vm.state.value.apps.any { it.name.contains("2048") })
        report.appendText("\nfavoritesSearch=true, returnedToLatest=true", Charsets.UTF_8)
    }

    @Test fun nativeDirectoryLoadsAndSearchesTheOfficialServer() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appSquareLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as KudoApplication).container.repository
        lateinit var vm: AppSquareViewModel
        compose.setContent { KudoTheme(DisplaySettings(theme = ThemeMode.LIGHT)) {
            vm = androidx.lifecycle.viewmodel.compose.viewModel {
                AppSquareViewModel(loadInfo = repository::miniAppDetails, load = repository::miniApps)
            }
            val state by vm.state.collectAsStateWithLifecycle()
            AppSquareScreen(state, vm::search, vm::retry, vm::more, {}, {}, {},
                sort = vm::sort, showAbout = vm::showAbout, dismissAbout = vm::dismissAbout, retryAbout = vm::retryAbout)
        } }
        fun awaitResults() {
            compose.waitUntil(45000) { !vm.state.value.loading }
            val state = vm.state.value
            File(context.getExternalFilesDir(null), "apps-live-result.txt").writeText(
                "count=${state.apps.size}, error=${state.error?.javaClass?.simpleName}: ${state.error?.message.orEmpty()}", Charsets.UTF_8)
            assertNull(state.error)
            compose.onNodeWithTag("app-${state.apps.first().id}").assertIsDisplayed()
        }
        try {
            awaitResults()
            assertTrue(vm.state.value.apps.isNotEmpty())
            capture("apps-live-grid.png")
            compose.onNodeWithContentDescription("切换列表布局").performClick()
            compose.onNodeWithTag("apps-list").assertIsDisplayed()
            val listed = vm.state.value.apps.first()
            listed.usageCount?.let { compose.onNodeWithTag("app-usage-${listed.id}", useUnmergedTree = true)
                .assertIsDisplayed().assertContentDescriptionEquals("使用 $it 人次") }
            listed.ratingCount?.takeIf { it > 0 }?.let { count -> compose.onNode(
                hasContentDescription("$count 人评分") and hasAnyAncestor(hasTestTag("app-${listed.id}")), useUnmergedTree = true).assertIsDisplayed() }
            capture("apps-live-list.png")
            compose.onNodeWithContentDescription("搜索应用").performClick()
            compose.onNodeWithTag("apps-search-field").performTextInput("2048")
            awaitResults()
            assertTrue(vm.state.value.apps.any { it.name.contains("2048") })
            capture("apps-live-search.png")
            val app = vm.state.value.apps.first { it.name.contains("2048") }
            compose.onNodeWithTag("app-${app.id}").performTouchInput { longClick() }
            compose.waitUntil(45000) { vm.state.value.about?.loading == false }
            assertNull(vm.state.value.about?.error)
            assertEquals(app.id, vm.state.value.about?.info?.id)
            compose.onNodeWithTag("mini-app-about").assertIsDisplayed()
            capture("apps-live-about.png")
            compose.onNodeWithText("关闭", substring = false).performClick()
            compose.onNodeWithTag("apps-back").performClick()
            for (sort in listOf(MiniAppSort.HOT, MiniAppSort.RATING, MiniAppSort.USED)) {
                compose.onNodeWithContentDescription("筛选应用").performClick()
                compose.onNodeWithTag("apps-sort-${sort.apiValue}").performClick()
                awaitResults()
                assertEquals(sort, vm.state.value.sort)
                assertEquals("2048", vm.state.value.query)
                compose.onNodeWithTag("apps-list").assertIsDisplayed()
            }
        } catch (error: Throwable) { capture("apps-live-failure.png"); throw error }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } } finally { bitmap.recycle() }
    }
}
