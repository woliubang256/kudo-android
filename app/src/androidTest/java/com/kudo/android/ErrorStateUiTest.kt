package com.kudo.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kudo.android.data.*
import com.kudo.android.ui.components.ErrorState
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ErrorStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rateLimitCountdownTicksAndRetriesOnceWhenItReachesZero() {
        var retries = 0
        compose.setContent {
            KudoTheme(DisplaySettings()) {
                ErrorState(
                    ApiFailure(FailureKind.RATE_LIMIT, "请求频繁\n请在 2 秒后重试", retryAfter = 2),
                    retry = { retries++ },
                    login = {},
                )
            }
        }

        compose.onNodeWithText("请在 2 秒后重试", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.waitUntil(2500) {
            compose.onAllNodes(hasText("请在 1 秒后重试", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { assertEquals(0, retries) }
        compose.waitUntil(5000) { retries == 1 }
        compose.waitUntil(1500) { retries == 1 }
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun manualRetryPreventsTheCountdownFromSubmittingAnotherRequest() {
        var retries = 0
        compose.setContent {
            KudoTheme(DisplaySettings()) {
                ErrorState(
                    ApiFailure(FailureKind.RATE_LIMIT, "cooldown 2 seconds", retryAfter = 2),
                    retry = { retries++ },
                    login = {},
                )
            }
        }

        compose.onNode(hasClickAction(), useUnmergedTree = true).performClick()
        compose.waitUntil(3500) { retries == 1 }
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
