package com.kudo.android.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class MiniAppDetails(
    val id: String, val name: String, val description: String, val iconUrl: String?,
    val version: String, val publisherMember: Member?,
    val ratingAverage: Double? = null, val ratingCount: Long? = null,
) {
    val publisher get() = publisherMember?.displayName.orEmpty()
    val publisherAvatarUrl get() = publisherMember?.avatar?.takeIf { it.isNotBlank() }

    companion object {
        fun parse(value: JSONObject, expectedId: String): MiniAppDetails {
            val app = value.getJSONObject("app")
            require(app.getString("app_id") == expectedId)
            val name = app.getString("name").trim().also { require(it.isNotEmpty()) }
            val author = app.optJSONObject("author")
            val icon = app.optString("icon_url").toHttpUrlOrNull()
                ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
            val avatar = listOf("avatar_template", "avatar_url").firstNotNullOfOrNull { key ->
                author?.optString(key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                    ?.replace("{size}", "128")?.let { "https://linux.do/".toHttpUrl().resolve(it) }
                    ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
            }
            val publisher = author?.let {
                Member(it.optInt("id"), it.optString("username"), it.optString("name"), avatar.orEmpty(),
                    avatarDetailsLoaded = true)
            }
            val average = (app.opt("rating_average") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..5.0 }
            val count = (app.opt("rating_count") as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
            return MiniAppDetails(expectedId, name, app.optString("description"), icon,
                app.optString("version"), publisher, average, count)
        }
    }
}
