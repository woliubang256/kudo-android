package com.kudo.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MiniAppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("kudo_mini_apps", Context.MODE_PRIVATE)
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    fun titleBar(accountId: Int?): Boolean = boolean("title_bar_${accountKey(accountId)}", true)

    fun saveTitleBar(accountId: Int?, visible: Boolean) {
        preferences.edit().putBoolean("title_bar_${accountKey(accountId)}", visible).apply()
        changes.value++
    }

    fun clipboardAllowed(accountId: Int?, appId: String, write: Boolean): Boolean =
        boolean(clipboardKey(accountId, appId, write), false)

    fun rememberClipboard(accountId: Int?, appId: String, write: Boolean) {
        preferences.edit().putBoolean(clipboardKey(accountId, appId, write), true).apply()
        changes.value++
    }

    private fun boolean(key: String, default: Boolean) =
        runCatching { preferences.getBoolean(key, default) }.getOrDefault(default)

    private fun clipboardKey(accountId: Int?, appId: String, write: Boolean): String {
        require(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}").matches(appId))
        return "clipboard_${accountKey(accountId)}_${appId}_${if (write) "write" else "read"}"
    }

    private fun accountKey(accountId: Int?): String {
        require(accountId == null || accountId > 0)
        return accountId?.let { "user_$it" } ?: "guest"
    }
}
