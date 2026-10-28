package com.kudo.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeTabsStore(context: Context) {
    private val prefs = context.getSharedPreferences("kudo_home_tabs", Context.MODE_PRIVATE)
    private val currentRevision = MutableStateFlow(0)
    val revision: StateFlow<Int> = currentRevision.asStateFlow()

    fun load(accountId: Int?): List<HomeTab> {
        val key = accountKey(accountId)
        val value = runCatching { prefs.getString(key, null) }.getOrNull()
        return decodeHomeTabs(value, accountId != null)
    }

    @Synchronized fun save(accountId: Int?, tabs: List<HomeTab>) {
        val key = accountKey(accountId)
        require(tabs.isNotEmpty()) { "至少保留一个首页 Tab" }
        val normalized = normalizeHomeTabs(tabs, accountId != null)
        prefs.edit().apply {
            if (normalized == defaultHomeTabs(accountId != null)) remove(key)
            else putString(key, encodeHomeTabs(normalized))
        }.apply()
        currentRevision.value += 1
    }

    private fun accountKey(accountId: Int?): String {
        require(accountId == null || accountId > 0) { "账号 ID 必须为正整数" }
        return accountId?.let { "user_$it" } ?: "guest"
    }
}
