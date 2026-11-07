package com.kudo.android.ui.web

internal data class MiniAppTaskKey(val accountId: Int?, val appId: String) {
    val account: String get() = accountId?.let { "user-$it" } ?: "guest"
    val identity: String get() = "kudo-mini-app://session/$account/$appId"
    val shortcutId: String get() = "mini-app-$account-$appId"
}

internal data class MiniAppTaskRequest(val key: MiniAppTaskKey, val url: String) {
    companion object {
        fun parse(url: String, accountId: Int?): MiniAppTaskRequest? {
            if (accountId != null && accountId <= 0) return null
            val id = MiniAppLocation.parse(url)?.id ?: return null
            return MiniAppTaskRequest(MiniAppTaskKey(accountId, id), miniAppPublicUrl(url))
        }
    }
}
