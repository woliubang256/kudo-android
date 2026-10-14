package com.kudo.android

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.account.HomeUpdateHost
import com.kudo.android.ui.account.HomeUpdateViewModel
import com.kudo.android.ui.theme.KudoTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeUpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val release = GitHubRelease("1.2.3", "新版", "## 新增\n- 首页检查更新", "2026-10-14", "$KudoSourceUrl/releases/tag/v1.2.3")
    private val models = mutableListOf<HomeUpdateViewModel>()
    private fun model(check: suspend () -> GitHubRelease) = HomeUpdateViewModel(check).also(models::add)
    @After fun stopModels() { models.forEach { it.viewModelScope.cancel() } }

    @Test fun onlyHomeChecksAndNewReleaseReusesPanelWithoutAnotherRequest() {
        var requests = 0
        var external = ""
        var active by mutableStateOf(false)
        val model = model { requests++; release }
        compose.setContent { KudoTheme(DisplaySettings()) {
            HomeUpdateHost(model, active, { external = it }) { Text("首页内容") }
        } }
        compose.runOnIdle { assertEquals(0, requests); active = true }
        compose.onNodeWithText("发现新版本 1.2.3").assertIsDisplayed()
        compose.onNodeWithText("• 首页检查更新").assertIsDisplayed()
        compose.onNodeWithText("正在检查 GitHub 版本…").assertDoesNotExist()
        compose.onNodeWithText("前往 GitHub 下载").performClick()
        compose.runOnIdle { assertEquals(release.url, external); assertEquals(1, requests) }
        compose.onNodeWithTag("modal-scrim").performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithText("首页内容").assertIsDisplayed()
        compose.runOnIdle { active = false }
        compose.waitForIdle()
        compose.runOnIdle { active = true }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun delayedReleaseWaitsUntilReturningToHome() {
        var active by mutableStateOf(true)
        var requests = 0
        val response = CompletableDeferred<GitHubRelease>()
        val model = model { requests++; response.await() }
        compose.setContent { KudoTheme(DisplaySettings()) {
            HomeUpdateHost(model, active, {}) { Text("页面内容") }
        } }
        compose.runOnIdle { assertEquals(1, requests); active = false }
        compose.waitForIdle()
        compose.runOnIdle { response.complete(release) }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithText("页面内容").assertIsDisplayed()
        compose.runOnIdle { active = true }
        compose.onNodeWithText("发现新版本 1.2.3").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun noUpdateAndNetworkFailureLeaveHomeSilentAndManualChecksAvailable() {
        var failures by mutableStateOf(false)
        var requests = 0
        val current = model { requests++; release.copy(version = BuildConfig.VERSION_NAME) }
        val failed = model { requests++; throw UpdateFailure("无法连接 GitHub") }
        compose.setContent { KudoTheme(DisplaySettings()) {
            HomeUpdateHost(if (failures) failed else current, true, {}) { Text("首页内容") }
        } }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithText("当前已是最新版本").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests); failures = true }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.onNodeWithText("无法连接 GitHub").assertDoesNotExist()
        compose.onNodeWithText("首页内容").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun pausedHomeDefersPanelUntilResumeWithoutRepeatingCheck() {
        lateinit var owner: Owner
        compose.runOnIdle { owner = Owner() }
        var requests = 0
        val response = CompletableDeferred<GitHubRelease>()
        val model = model { requests++; response.await() }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            KudoTheme(DisplaySettings()) { HomeUpdateHost(model, true, {}) { Text("首页内容") } }
        } }
        compose.runOnIdle { assertEquals(1, requests); owner.lifecycle.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        compose.runOnIdle { response.complete(release) }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("发现新版本 1.2.3").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun recomposingHomeAfterRestorationDoesNotCheckOrRemindAgain() {
        var requests = 0
        val model = model { requests++; release }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { KudoTheme(DisplaySettings()) {
            HomeUpdateHost(model, true, {}) { Text("首页内容") }
        } }
        compose.onNodeWithText("发现新版本 1.2.3").assertIsDisplayed()
        compose.onNodeWithTag("modal-scrim").performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("liquid-action-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    }
}
