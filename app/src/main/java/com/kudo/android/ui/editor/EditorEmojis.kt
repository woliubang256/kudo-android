package com.kudo.android.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kudo.android.ui.components.CommunityTextField
import com.kudo.android.ui.components.LocalAvatarMetadata
import com.kudo.android.ui.topic.SiteEmoji

private val commonEditorEmojis = listOf("smile", "grin", "joy", "rofl", "laughing", "sweat_smile", "wink", "blush",
    "heart_eyes", "kissing_heart", "thinking", "sunglasses", "sweat", "cry", "sob", "angry", "+1", "-1", "clap",
    "pray", "muscle", "wave", "ok_hand", "eyes", "heart", "broken_heart", "fire", "tada", "rocket", "100", "warning", "white_check_mark")

@Composable
internal fun EditorEmojis(insert: (String) -> Unit) {
    val metadata = LocalAvatarMetadata.current
    var urls by remember(metadata) { mutableStateOf<Map<String, String>?>(null) }
    var search by remember { mutableStateOf("") }
    LaunchedEffect(metadata) { urls = metadata?.emojiDirectory().orEmpty() }
    CommunityTextField(search, { search = it }, Modifier.fillMaxWidth().testTag("editor-emoji-search"), placeholder = "搜索表情名称", singleLine = true)
    if (urls == null) Text("正在读取站点表情…", style = MaterialTheme.typography.bodySmall)
    else if (urls.isNullOrEmpty()) Text("站点表情暂未获取，仍可使用常用表情", style = MaterialTheme.typography.bodySmall)
    val names = remember(urls, search) { (commonEditorEmojis + urls.orEmpty().keys).distinct().filter { it.contains(search.trim(), ignoreCase = true) } }
    if (names.isEmpty()) Text("没有匹配的表情")
    LazyVerticalGrid(GridCells.Adaptive(52.dp), Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("editor-emojis")) {
        items(names, key = { it }) { name ->
            Box(Modifier.size(52.dp).semantics { contentDescription = ":$name:" }.clickable(role = Role.Button) { insert(":$name:") }, contentAlignment = Alignment.Center) {
                SiteEmoji(name, Modifier.size(30.dp), urls?.get(name))
            }
        }
    }
}
