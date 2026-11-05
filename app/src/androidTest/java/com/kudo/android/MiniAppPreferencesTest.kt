package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.MiniAppPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MiniAppPreferencesTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "mini_app_preferences_test_${UUID.randomUUID()}"
    private val fixture = object : ContextWrapper(base) {
        override fun getSharedPreferences(key: String, mode: Int): SharedPreferences = super.getSharedPreferences(name, mode)
    }
    private val first = "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"
    private val second = "01a1966e-935a-79e0-8c76-9b0f7552c578"
    @After fun cleanup() { base.deleteSharedPreferences(name) }

    @Test fun defaultsDoNotWriteAndTitleBarSurvivesReopeningForEachAccount() {
        val store = MiniAppPreferences(fixture)
        for (account in listOf(null, 101, 202)) {
            assertTrue(store.titleBar(account))
            assertFalse(store.clipboardAllowed(account, first, false))
        }
        assertTrue(fixture.getSharedPreferences("kudo_mini_apps", 0).all.isEmpty())
        store.saveTitleBar(101, false)
        store.saveTitleBar(null, false)
        val reopened = MiniAppPreferences(fixture)
        assertFalse(reopened.titleBar(101))
        assertFalse(reopened.titleBar(null))
        assertTrue(reopened.titleBar(202))
        reopened.saveTitleBar(101, true)
        assertTrue(MiniAppPreferences(fixture).titleBar(101))
        assertFalse(MiniAppPreferences(fixture).titleBar(null))
    }

    @Test fun clipboardGrantsAreSeparatedByAccountAppAndOperationAcrossReopening() {
        val store = MiniAppPreferences(fixture)
        store.rememberClipboard(101, first, true)
        val reopened = MiniAppPreferences(fixture)
        for (account in listOf(null, 101, 202)) for (app in listOf(first, second)) for (write in listOf(false, true)) {
            assertEquals(account == 101 && app == first && write, reopened.clipboardAllowed(account, app, write))
        }
        reopened.rememberClipboard(null, second, false)
        assertTrue(MiniAppPreferences(fixture).clipboardAllowed(null, second, false))
        assertFalse(MiniAppPreferences(fixture).clipboardAllowed(101, second, false))
        assertTrue(reopened.titleBar(101))
    }

    @Test fun invalidKeysAreRejectedAndWrongTypesUseSafeDefaults() {
        val store = MiniAppPreferences(fixture)
        assertThrows(IllegalArgumentException::class.java) { store.saveTitleBar(0, false) }
        assertThrows(IllegalArgumentException::class.java) { store.rememberClipboard(101, "all", true) }
        val prefs = fixture.getSharedPreferences("kudo_mini_apps", 0)
        prefs.edit().putString("title_bar_user_101", "bad")
            .putString("clipboard_user_101_${first}_write", "true").commit()
        assertTrue(store.titleBar(101))
        assertFalse(store.clipboardAllowed(101, first, true))
    }
}
