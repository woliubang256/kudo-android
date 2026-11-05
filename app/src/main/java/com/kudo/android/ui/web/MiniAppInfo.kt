package com.kudo.android.ui.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class MiniAppLocation(val id: String?) {
    companion object {
        private val appId = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")

        fun parse(value: String): MiniAppLocation? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.host != "linux.do" || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val path = url.pathSegments.dropLastWhile(String::isEmpty)
            if (path == listOf("apps")) return MiniAppLocation(null)
            return path.takeIf { it.size == 2 && it[0] == "apps" && appId.matches(it[1]) }
                ?.let { MiniAppLocation(it[1]) }
        }
    }
}

internal fun miniAppEmbeddedUrl(value: String): String = if (MiniAppLocation.parse(value)?.id == null) value
    else value.toHttpUrlOrNull()!!.newBuilder().setQueryParameter("embed_mode", "true").build().toString()

internal fun miniAppPublicUrl(value: String): String = if (MiniAppLocation.parse(value)?.id == null) value
    else value.toHttpUrlOrNull()!!.newBuilder().removeAllQueryParameters("embed_mode").build().toString()

internal typealias MiniAppInfo = com.kudo.android.data.MiniAppDetails
