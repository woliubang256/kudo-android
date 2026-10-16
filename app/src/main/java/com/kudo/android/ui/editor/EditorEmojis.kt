package com.kudo.android.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.semantics.selected
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kudo.android.data.AppSettings
import com.kudo.android.data.AvatarMetadataCache
import com.kudo.android.data.SiteEmoji
import com.kudo.android.data.SiteEmojiGroup
import com.kudo.android.ui.components.CommunityTextField
import com.kudo.android.ui.components.LocalAvatarMetadata
import com.kudo.android.ui.components.showKudoToast
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

private val groupIconNames = mapOf(
    "smileys_&_emotion" to "smile",
    "people_&_body" to "wave",
    "animals_&_nature" to "monkey_face",
    "food_&_drink" to "grapes",
    "travel_&_places" to "earth_americas",
    "activities" to "soccer",
    "objects" to "bulb",
    "symbols" to "heart",
    "flags" to "flag_us",
)

private fun groupIcon(group: SiteEmojiGroup): SiteEmoji? {
    val preferred = groupIconNames[group.name]
    return preferred?.let { name -> group.items.firstOrNull { it.name == name } }
        ?: group.items.firstOrNull()
}

@Composable
internal fun EditorEmojis(insert: (String) -> Unit) {
    val metadata = LocalAvatarMetadata.current
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context) }
    var groups by remember(metadata) { mutableStateOf<List<SiteEmojiGroup>?>(null) }
    var search by remember { mutableStateOf("") }
    val pager = rememberPagerState(pageCount = { (groups?.size ?: 0) + 1 })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) {
        var previous = pager.settledPage
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            if (page != previous) search = ""
            previous = page
        }
    }
    var recent by remember { mutableStateOf(settings.recentEmojis()) }
    LaunchedEffect(metadata) { groups = metadata?.emojiDirectory()?.takeIf { it.isNotEmpty() } ?: listOf(SiteEmojiGroup("smileys_&_emotion", fallbackEditorEmojis)) }

    val available = groups.orEmpty()
    val all = remember(available) { available.flatMap { it.items }.associateBy { it.name } }
    val currentRecent = remember(recent, all) { recent.mapNotNull(all::get) }
    val searchResult = remember(search, available) {
        search.trim().takeIf(String::isNotEmpty)?.let { query -> available.flatMap { it.items }.filter { it.name.contains(query, ignoreCase = true) } }
    }
    fun choose(item: SiteEmoji) {
        settings.rememberEmoji(item.name); recent = settings.recentEmojis(); insert(":${item.name}:")
    }

    CommunityTextField(search, { search = it }, Modifier.fillMaxWidth().testTag("editor-emoji-search"), placeholder = "搜索表情", singleLine = true)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("editor-emoji-groups"), verticalAlignment = Alignment.CenterVertically) {
        EmojiGroupTab(
            label = "最近使用",
            testTag = "editor-emoji-tab-recent",
            icon = SiteEmoji("star", AvatarMetadataCache.standardEmojiUrl("star").orEmpty(), "recent"),
            selected = pager.currentPage == 0 && search.isBlank(),
        ) { search = ""; scope.launch { pager.animateScrollToPage(0) } }
        available.forEachIndexed { index, group ->
            EmojiGroupTab(
                label = groupTitle(group.name),
                testTag = "editor-emoji-tab-$index",
                icon = groupIcon(group),
                selected = pager.currentPage == index + 1 && search.isBlank(),
            ) { search = ""; scope.launch { pager.animateScrollToPage(index + 1) } }
        }
    }
    if (groups == null) Text("正在读取站点表情…", style = MaterialTheme.typography.bodySmall)
    else HorizontalPager(pager, Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("editor-emoji-pager"),
        key = { if (it == 0) "recent" else available[it - 1].name }) { index ->
        val searching = searchResult != null && index == pager.settledPage
        val entries = if (searching) searchResult.orEmpty() else if (index == 0) currentRecent else available[index - 1].items
        if (entries.isEmpty()) Box(Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (!searching && index == 0) "暂无表情" else "没有匹配的表情", style = MaterialTheme.typography.bodySmall)
        } else LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxWidth().heightIn(max = 320.dp)
            .testTag(if (index == pager.settledPage) "editor-emojis" else "editor-emojis-$index")) {
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
private fun EmojiGroupTab(
    label: String,
    testTag: String,
    icon: SiteEmoji?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) bring.bringIntoView() }
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .testTag(testTag)
            .bringIntoViewRequester(bring)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                role = Role.Tab,
                onClick = onClick,
                onLongClickLabel = "显示分组名称",
                onLongClick = { showKudoToast(context, label) },
            )
            .semantics {
                this.selected = selected
                contentDescription = label
                stateDescription = if (selected) "已选择" else "未选择"
            }
            .background(background)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            SiteEmojiView(icon.name, Modifier.size(24.dp), icon.url)
        } else {
            Icon(Icons.Rounded.Mood, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
