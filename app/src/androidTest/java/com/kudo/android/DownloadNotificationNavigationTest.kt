package com.kudo.android

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.ui.KudoApp
import org.junit.Rule
import org.junit.Test

class DownloadNotificationNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun notificationActionOpensDownloadManagementAndBackReturns() {
        val app = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KudoApplication).container
        var incoming by mutableStateOf(false)
        compose.setContent { KudoApp(app, incomingDownloads = incoming, consumeDownloads = { incoming = false }) }
        compose.runOnIdle { incoming = true }
        compose.onNodeWithTag("downloads-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("downloads-screen").assertDoesNotExist()
    }
}
