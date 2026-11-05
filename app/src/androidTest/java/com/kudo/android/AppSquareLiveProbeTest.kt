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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in read-only probe through the installed app's actual session and network route. */
class AppSquareLiveProbeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
            AppSquareScreen(state, vm::search, vm::retry, vm::more, {}, {}, {})
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
            capture("apps-live-list.png")
            compose.onNodeWithContentDescription("搜索应用").performClick()
            compose.onNodeWithTag("apps-search-field").performTextInput("2048")
            awaitResults()
            assertTrue(vm.state.value.apps.any { it.name.contains("2048") })
            capture("apps-live-search.png")
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
