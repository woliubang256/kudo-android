package com.kudo.android.data

import org.json.JSONObject
import org.jsoup.Jsoup

data class ReplyRules(val minimum: Int, val maximum: Int) {
    init { require(minimum > 0 && maximum >= minimum) }
    fun problem(raw: String): String? = when {
        raw.length > maximum -> "回复最多 $maximum 字"
        replyLength(raw) < minimum -> "回复至少 $minimum 字，还需 ${minimum - replyLength(raw)} 字"
        else -> null
    }
}


data class PersonalMessageRules(val minimum: Int, val maximum: Int) {
    init { require(minimum > 0 && maximum >= minimum) }
    fun problem(raw: String): String? = when {
        raw.length > maximum -> "Private message body is limited to $maximum characters"
        replyLength(raw) < minimum -> "Private message body requires at least $minimum characters"
        else -> null
    }
}

/** Discourse composer counts UTF-16 units, collapses its whitespace set, and excludes BBCode quotes/comments. */
fun replyLength(raw: String): Int {
    if (raw.length > 10_000) return raw.length
    var text = raw
    val comment = Regex("<!--(.*?)-->")
    val quote = Regex("\\[quote=([^\\]]*)\\]((?:[\\s\\S](?!\\[quote=[^\\]]*\\]))*?)\\[/quote\\]", RegexOption.IGNORE_CASE)
    while (comment.containsMatchIn(text)) text = comment.replace(text, "")
    while (quote.containsMatchIn(text)) text = quote.replace(text, "")
    var count = 0
    var space = true
    text.forEach { char ->
        val whitespace = char.code in 0x2000..0x200A || when (char.code) {
            9, 10, 11, 12, 13, 32, 160, 5760, 8239, 8287, 12288 -> true
            else -> false
        }
        if (whitespace) { if (!space) { count++; space = true } }
        else { count++; space = false }
    }
    return if (count > 0 && space) count - 1 else count
}

internal fun siteSettings(html: String): JSONObject {
    val document = Jsoup.parse(html)
    val preload = document.selectFirst("[data-preloaded]")?.attr("data-preloaded")
        ?: document.getElementById("data-preloaded")?.data()
        ?: throw ApiFailure(FailureKind.SERVER, "暂时无法读取社区字数规则")
    val data = JSONObject(preload)
    return data.optJSONObject("siteSettings") ?: data.optString("siteSettings").takeIf(String::isNotBlank)?.let(::JSONObject)
        ?: throw ApiFailure(FailureKind.SERVER, "暂时无法读取社区字数规则")
}

internal fun parseReplyRules(html: String, site: JSONObject, categoryId: Int): ReplyRules {
    val settings = siteSettings(html)
    val minimum = settings.positiveInt("min_post_length") ?: throw ApiFailure(FailureKind.SERVER, "暂时无法读取最小回帖字数")
    val maximum = settings.positiveInt("max_post_length") ?: throw ApiFailure(FailureKind.SERVER, "暂时无法读取最大回帖字数")
    val categories = site.optJSONArray("categories")?.objects() ?: throw ApiFailure(FailureKind.SERVER, "暂时无法读取分类回帖规则")
    val category = categories.firstOrNull { it.optInt("id") == categoryId }
        ?: throw ApiFailure(FailureKind.SERVER, "暂时无法读取当前分类回帖规则")
    val override = category.optJSONObject("custom_fields")?.positiveInt("warden_min_post_length")
    return ReplyRules((override ?: minimum).coerceAtMost(maximum), maximum)
}

internal fun parsePersonalMessageRules(html: String): PersonalMessageRules {
    val settings = siteSettings(html)
    val minimum = settings.positiveInt("min_personal_message_post_length") ?: throw ApiFailure(FailureKind.SERVER, "Missing private message minimum")
    val maximum = settings.positiveInt("max_post_length") ?: throw ApiFailure(FailureKind.SERVER, "Missing private message maximum")
    return PersonalMessageRules(minimum.coerceAtMost(maximum), maximum)
}

suspend fun CommunityRepository.replyRules(topicId: Int, categoryId: Int? = null): ReplyRules {
    require(topicId > 0)
    val category = categoryId ?: detail(topicId).categoryId
    return parseReplyRules(client.siteSettingsHtml(), client.get("/site.json"), category)
}


suspend fun CommunityRepository.personalMessageRules(): PersonalMessageRules = parsePersonalMessageRules(client.siteSettingsHtml())
