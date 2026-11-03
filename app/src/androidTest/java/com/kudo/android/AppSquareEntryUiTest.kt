package com.kudo.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.account.ProfileScreen
import com.kudo.android.ui.theme.KudoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppSquareEntryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun guestAndMemberCanOpenAppsAboveSettingsWithoutLoggingIn() {
        var member by mutableStateOf<Member?>(null)
        var opened = 0
        var logins = 0
        compose.setContent { KudoTheme(DisplaySettings()) {
            ProfileScreen(member, { logins++ }, {}, {}, {}, apps = { opened++ })
        } }
        for (account in listOf(null, Member(42, "fixture", "应用广场测试", ""), null)) {
            compose.runOnIdle { member = account }
            val apps = compose.onNodeWithText("应用广场").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val appBounds = apps.fetchSemanticsNode().boundsInRoot
            val settingsBounds = compose.onNodeWithText("设置").fetchSemanticsNode().boundsInRoot
            assertTrue("应用广场位于设置上方", appBounds.bottom <= settingsBounds.top)
            apps.performClick()
        }
        compose.runOnIdle { assertEquals(3, opened); assertEquals(0, logins) }
    }
}
