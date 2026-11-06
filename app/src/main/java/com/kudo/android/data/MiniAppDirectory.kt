package com.kudo.android.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

data class MiniAppEntry(val id: String, val name: String, val iconUrl: String?,
    val ratingAverage: Double? = null, val ratingCount: Long? = null, val usageCount: Long? = null) {
    val url: String get() = "https://linux.do/apps/$id"
}

data class MiniAppDirectoryPage(val apps: List<MiniAppEntry>, val hasMore: Boolean)

enum class MiniAppSort(val apiValue: String, val title: String, val favorite: Boolean = false) {
    HOT("hot", "最热"), LATEST("latest", "最新上线"), RATING("rating", "评分最高"), USED("used", "使用最多"),
    FAVORITES("latest", "已收藏", favorite = true);

    val key: String get() = if (favorite) "favorites" else apiValue
}

private val miniAppId = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")

internal fun parseMiniAppDirectory(data: JSONObject, page: Int): MiniAppDirectoryPage {
    require(data.getInt("page") == page) { "应用目录页码不匹配" }
    val perPage = data.getInt("per_page").also { require(it > 0) }
    val total = data.getInt("total").also { require(it >= 0) }
    val values = data.getJSONArray("apps")
    val apps = buildList {
        for (i in 0 until values.length()) {
            val app = values.optJSONObject(i) ?: continue
            val id = app.optString("app_id").takeIf(miniAppId::matches) ?: continue
            val name = app.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
            val icon = app.optString("icon_url").toHttpUrlOrNull()
                ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
            val average = (app.opt("rating_average") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..5.0 }
            val ratings = (app.opt("rating_count") as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
            val usage = (app.opt("usage_count") as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
            add(MiniAppEntry(id, name, icon, average, ratings, usage))
        }
    }.distinctBy(MiniAppEntry::id)
    return MiniAppDirectoryPage(apps, values.length() > 0 && (page + 1L) * perPage < total)
}

suspend fun CommunityRepository.miniApps(query: String = "", page: Int = 0, sort: MiniAppSort = MiniAppSort.LATEST): MiniAppDirectoryPage {
    require(page >= 0)
    return parseMiniAppDirectory(client.get("/mini-app-api/apps.json",
        mapOf("sort" to sort.apiValue, "q" to query.trim(), "page" to page.toString(),
            "favorite" to sort.favorite.toString())), page)
}

suspend fun CommunityRepository.miniAppDetails(id: String): MiniAppDetails {
    require(miniAppId.matches(id))
    return MiniAppDetails.parse(client.get("/mini-app-api/public/$id/info"), id)
}
