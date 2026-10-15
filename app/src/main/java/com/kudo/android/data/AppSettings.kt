package com.kudo.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
enum class GlassMode(val label: String) { LIQUID("液态玻璃"), BLUR("背景模糊"), TRANSLUCENT("半透明") }
enum class TopicListStyle(val label: String) { FEED("信息流"), TITLE("传统标题") }
enum class NavigationAction(val label: String) { COMPOSE("发帖"), SEARCH("搜索") }
enum class ExternalLinkMode(val label: String) { SYSTEM("系统打开"), IN_APP("应用内打开") }
data class DisplaySettings(val theme: ThemeMode = ThemeMode.SYSTEM, val glass: GlassMode = GlassMode.LIQUID, val fontScale: Float = 1f, val topicListStyle: TopicListStyle = TopicListStyle.FEED, val showSignatures: Boolean = true, val navigationAction: NavigationAction = NavigationAction.COMPOSE, val showScrollToTop: Boolean = true, val compactPinnedTopics: Boolean = true, val externalLinkMode: ExternalLinkMode = ExternalLinkMode.SYSTEM)
data class Draft(val title: String = "", val raw: String = "", val categoryId: Int = 0, val recipients: String = "", val tags: List<String> = emptyList())

internal fun decodeDraftTags(value: String?): List<String> = runCatching { JSONArray(value ?: "[]").tagNames() }.getOrDefault(emptyList())

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kudo_preferences", Context.MODE_PRIVATE)
    private val _display = MutableStateFlow(DisplaySettings(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", "") } ?: ThemeMode.SYSTEM,
        GlassMode.entries.firstOrNull { it.name == prefs.getString("glass", "") } ?: GlassMode.LIQUID,
        prefs.getFloat("font_scale", 1f).coerceIn(0.85f, 1.3f),
        TopicListStyle.entries.firstOrNull { it.name == prefs.getString("topic_list_style", "") } ?: TopicListStyle.FEED,
        prefs.getBoolean("show_signatures", true),
        NavigationAction.entries.firstOrNull { it.name == prefs.getString("navigation_action", "") } ?: NavigationAction.COMPOSE,
        prefs.getBoolean("show_scroll_to_top", true),
        prefs.getBoolean("compact_pinned_topics", true),
        ExternalLinkMode.entries.firstOrNull { it.name == prefs.getString("external_link_mode", "") } ?: ExternalLinkMode.SYSTEM,
    ))
    val display = _display.asStateFlow()
    fun update(value: DisplaySettings) {
        _display.value = value
        prefs.edit().putString("theme", value.theme.name).putString("glass", value.glass.name).putFloat("font_scale", value.fontScale).putString("topic_list_style", value.topicListStyle.name).putBoolean("show_signatures", value.showSignatures).putString("navigation_action", value.navigationAction.name).putBoolean("show_scroll_to_top", value.showScrollToTop).putBoolean("compact_pinned_topics", value.compactPinnedTopics).putString("external_link_mode", value.externalLinkMode.name).apply()
    }
    fun draft(key: String): Draft = Draft(prefs.getString("draft_${key}_title", "").orEmpty(), prefs.getString("draft_${key}_raw", "").orEmpty(), prefs.getInt("draft_${key}_category", 0), prefs.getString("draft_${key}_recipients", "").orEmpty(), decodeDraftTags(prefs.getString("draft_${key}_tags", null)))
    fun saveDraft(key: String, draft: Draft) { prefs.edit().putString("draft_${key}_title", draft.title).putString("draft_${key}_raw", draft.raw).putInt("draft_${key}_category", draft.categoryId).putString("draft_${key}_recipients", draft.recipients).putString("draft_${key}_tags", JSONArray(draft.tags).toString()).apply() }
    fun clearDraft(key: String) { prefs.edit().remove("draft_${key}_title").remove("draft_${key}_raw").remove("draft_${key}_category").remove("draft_${key}_recipients").remove("draft_${key}_tags").apply() }
    fun drafts(userId: Int): List<SavedDraft> = localAccountDrafts(prefs.all, userId)
    fun postEditDraft(userId: Int, postId: Int): PostEditDraft? = prefs.getString("post_edit_${userId}_$postId", null)
        ?.let { runCatching { PostEditDraft.fromJson(it) }.getOrNull() }
    fun savePostEditDraft(userId: Int, postId: Int, draft: PostEditDraft?) {
        val key = "post_edit_${userId}_$postId"
        prefs.edit().apply { if (draft == null) remove(key) else putString(key, draft.toJson()) }.apply()
    }
    fun clearAccountData() {
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("draft_") || it.startsWith("post_edit_") }.forEach(::remove) }.apply()
    }
    fun recentEmojis(): List<String> = prefs.getString("editor_recent_emojis", "").orEmpty().split(',').filter(String::isNotBlank)
    fun rememberEmoji(name: String) {
        if (name.isBlank()) return
        val values = (listOf(name) + recentEmojis().filterNot { it == name }).take(32)
        prefs.edit().putString("editor_recent_emojis", values.joinToString(",")).apply()
    }
}
