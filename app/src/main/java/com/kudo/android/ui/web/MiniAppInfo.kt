package com.kudo.android.ui.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

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

internal data class MiniAppInfo(
    val id: String, val name: String, val description: String, val iconUrl: String?,
    val version: String, val publisher: String, val publisherAvatarUrl: String? = null,
) {
    companion object {
        fun parse(value: JSONObject, expectedId: String): MiniAppInfo {
            val app = value.getJSONObject("app")
            require(app.getString("app_id") == expectedId)
            val name = app.getString("name").trim().also { require(it.isNotEmpty()) }
            val author = app.optJSONObject("author")
            val publisher = author?.optString("name")?.takeIf { it.isNotBlank() }
                ?: author?.optString("username").orEmpty()
            val icon = app.optString("icon_url").toHttpUrlOrNull()
                ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
            val avatar = listOf("avatar_template", "avatar_url").firstNotNullOfOrNull { key ->
                author?.optString(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                    ?.replace("{size}", "128")?.let { "https://linux.do/".toHttpUrl().resolve(it) }
                    ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
            }
            return MiniAppInfo(expectedId, name, app.optString("description"), icon,
                app.optString("version"), publisher, avatar)
        }
    }
}
