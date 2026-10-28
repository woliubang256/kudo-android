package com.kudo.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kudo.android.data.SavedImage
import com.kudo.android.ui.components.CommunityButton
import com.kudo.android.ui.components.CommunityLoadingIndicator
import kotlinx.coroutines.launch

@Composable
internal fun ImageLibraryPicker(enabled: Boolean, insert: (List<SavedImage>) -> Unit) {
    val state = LocalImageLibrary.current
    val scope = rememberCoroutineScope()
    var folder by remember(state) { mutableStateOf<String?>(null) }
    var chosen by remember(state) { mutableStateOf<List<String>>(emptyList()) }
    val library = state?.library
    val selected = chosen.mapNotNull { id -> library?.images?.firstOrNull { it.id == id } }.distinctBy { it.originalUrl }
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .45f }.coerceIn(120.dp, 420.dp)
    if (state?.error != null) {
        Text(state.error!!)
        CommunityButton("重试", { scope.launch { state.reload() } })
    } else if (library == null) CommunityLoadingIndicator(Modifier.padding(16.dp))
    else {
        ImageLibraryBrowser(library, folder, { folder = it }, { image ->
            if (enabled) chosen = if (image.id in chosen) chosen - image.id else chosen + image.id
        }, Modifier.fillMaxWidth().height(height).testTag("editor-library-picker"), selection = chosen.toSet())
        Text("使用原图链接，不重新上传", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CommunityButton(if (selected.isEmpty()) "选择图片后插入" else "插入 ${selected.size} 张图片", { insert(selected) },
            Modifier.fillMaxWidth().testTag("editor-library-insert"), enabled = enabled && selected.isNotEmpty())
    }
}
