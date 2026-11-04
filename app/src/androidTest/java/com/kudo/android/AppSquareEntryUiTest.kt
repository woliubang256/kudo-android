package com.kudo.android

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.account.ProfileScreen
import com.kudo.android.ui.theme.KudoTheme
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppSquareEntryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun guestAndMemberCanOpenAppsAboveSettingsWithoutLoggingIn() {
        var member by mutableStateOf<Member?>(null)
        var opened = 0
        var logins = 0
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { KudoTheme(DisplaySettings(theme = theme)) {
            ProfileScreen(member, { logins++ }, {}, {}, {}, apps = { opened++ })
        } }
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
        compose.runOnIdle { theme = mode }
        for (account in listOf(null, Member(42, "fixture", "应用广场测试", ""), null)) {
            compose.runOnIdle { member = account }
            val apps = compose.onNodeWithText("应用广场").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val appBounds = apps.fetchSemanticsNode().boundsInRoot
            val settingsBounds = compose.onNodeWithText("设置").fetchSemanticsNode().boundsInRoot
            assertTrue("应用广场位于设置上方", appBounds.bottom <= settingsBounds.top)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "apps-entry-${if (account == null) "guest" else "member"}-${mode.name.lowercase()}.png")
                .outputStream().use { apps.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            apps.performClick()
        }
        }
        compose.runOnIdle { assertEquals(6, opened); assertEquals(0, logins) }
    }
}
