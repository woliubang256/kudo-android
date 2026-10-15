package com.kudo.android.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kudo.android.data.AppSettings
import com.kudo.android.data.AvatarMetadataCache
import com.kudo.android.data.SiteEmoji
import com.kudo.android.data.SiteEmojiGroup
import com.kudo.android.ui.components.CommunityTextField
import com.kudo.android.ui.components.LocalAvatarMetadata
import com.kudo.android.ui.topic.SiteEmoji as SiteEmojiView

private val fallbackEditorEmojis = listOf(
    "grinning", "smiley", "smile", "grin", "laughing", "sweat_smile", "joy", "rofl", "blush", "innocent",
    "slightly_smiling_face", "upside_down_face", "wink", "heart_eyes", "kissing_heart", "thinking", "sunglasses", "sob",
    "rage", "+1", "-1", "clap", "pray", "muscle", "wave", "eyes", "heart", "broken_heart", "fire", "tada", "rocket",
).map { name -> SiteEmoji(name, AvatarMetadataCache.standardEmojiUrl(name).orEmpty(), "smileys_&_emotion") }

private fun groupTitle(name: String): String = when (name) {
    "smileys_&_emotion" -> "表情与情绪"
    "people_&_body" -> "人物与身体"
    "animals_&_nature" -> "动物与自然"
    "food_&_drink" -> "食物与饮料"
    "travel_&_places" -> "旅行与地点"
    "activities" -> "活动"
    "objects" -> "物品"
    "symbols" -> "符号"
    "flags" -> "旗帜"
    "custom" -> "自定义"
    else -> name
}

@Composable
internal fun EditorEmojis(insert: (String) -> Unit) {
    val metadata = LocalAvatarMetadata.current
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    var groups by remember(metadata) { mutableStateOf<List<SiteEmojiGroup>?>(null) }
    var search by remember { mutableStateOf("") }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(settings.recentEmojis()) }
    LaunchedEffect(metadata) { groups = metadata?.emojiDirectory()?.takeIf { it.isNotEmpty() } ?: listOf(SiteEmojiGroup("smileys_&_emotion", fallbackEditorEmojis)) }

    val available = groups.orEmpty()
    val all = remember(available) { available.flatMap { it.items }.associateBy { it.name } }
    val currentRecent = remember(recent, all) { recent.mapNotNull(all::get) }
    val searchResult = remember(search, available) {
        search.trim().takeIf(String::isNotEmpty)?.let { query -> available.flatMap { it.items }.filter { it.name.contains(query, ignoreCase = true) } }
    }
    val shownGroup = selectedGroup?.let { name -> available.firstOrNull { it.name == name } }
    val entries = when {
        searchResult != null -> searchResult
        selectedGroup == null -> currentRecent
        else -> shownGroup?.items.orEmpty()
    }
    val heading = when {
        searchResult != null -> "搜索结果"
        selectedGroup == null -> "最近使用"
        else -> groupTitle(selectedGroup.orEmpty())
    }
    fun choose(item: SiteEmoji) {
        settings.rememberEmoji(item.name); recent = settings.recentEmojis(); insert(":${item.name}:")
    }

    CommunityTextField(search, { search = it }, Modifier.fillMaxWidth().testTag("editor-emoji-search"), placeholder = "搜索表情", singleLine = true)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("editor-emoji-groups"), verticalAlignment = Alignment.CenterVertically) {
        EmojiGroupTab("最近", selectedGroup == null && search.isBlank()) { selectedGroup = null; search = "" }
        available.forEach { group ->
            EmojiGroupTab(groupTitle(group.name), selectedGroup == group.name && search.isBlank()) { selectedGroup = group.name; search = "" }
        }
    }
    Text(heading, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall)
    when {
        groups == null -> Text("正在读取站点表情…", style = MaterialTheme.typography.bodySmall)
        entries.isEmpty() && searchResult == null && selectedGroup == null -> Text("还没有最近使用的表情", style = MaterialTheme.typography.bodySmall)
        entries.isEmpty() -> Text("没有匹配的表情", style = MaterialTheme.typography.bodySmall)
        else -> LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("editor-emojis")) {
            items(entries, key = { "${it.group}:${it.name}" }) { item ->
                Box(Modifier.size(52.dp).semantics { contentDescription = ":${item.name}:" }
                    .clickable(role = Role.Button) { choose(item) }, contentAlignment = Alignment.Center) {
                    if (item.url.isBlank()) Text(item.name, style = MaterialTheme.typography.headlineSmall)
                    else SiteEmojiView(item.name, Modifier.size(30.dp), item.url)
                }
            }
        }
    }
}

@Composable
private fun EmojiGroupTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, Modifier.clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium)
}
