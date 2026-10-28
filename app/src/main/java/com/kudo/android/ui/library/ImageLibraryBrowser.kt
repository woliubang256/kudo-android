package com.kudo.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kudo.android.data.*
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.EmptyState
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import com.kudo.android.ui.topic.ArticleBlock

internal fun SavedImage.articleImage() = ArticleBlock.Image(thumbnailUrl, alt, ratio, originalUrl, filename)

@Composable
internal fun ImageLibraryBrowser(library: ImageLibrary, folderId: String?, selectFolder: (String?) -> Unit,
    open: (SavedImage) -> Unit, modifier: Modifier = Modifier, selection: Set<String>? = null,
    source: ((ImageSource) -> Unit)? = null, remove: ((SavedImage) -> Unit)? = null) {
    val images = remember(library, folderId) { library.images.filter { folderId == null || it.folderId == folderId } }
    Column(modifier) {
        LazyRow(Modifier.fillMaxWidth().testTag("library-folders"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(ImageFolder("all", "全部")) + library.folders, key = { it.id }) { folder ->
                val id = folder.id.takeUnless { it == "all" }
                val active = folderId == id
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (active) LogoYellow else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(role = Role.Tab) { selectFolder(id) }.semantics { selected = active }
                    .heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp).testTag("library-folder-${folder.id}")) {
                    Text(folder.name, color = if (active) Ink else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (images.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState("暂无收藏图片", "长按帖子里的图片，即可收藏到目录。")
        } else LazyVerticalGrid(GridCells.Adaptive(148.dp), Modifier.weight(1f).fillMaxWidth().testTag("library-images"),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(images, key = { it.id }) { image ->
                val checked = selection?.contains(image.id) == true
                Column(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).testTag("library-item-${image.id}")) {
                    var failed by remember(image.thumbnailUrl) { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().aspectRatio(1.15f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
                        .clickable(role = if (selection != null) Role.Checkbox else Role.Button) { open(image) }
                        .semantics { if (selection != null) selected = checked }.testTag("library-image-${image.id}")) {
                        AsyncImage(image.thumbnailUrl, image.alt.ifBlank { "收藏图片" }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                            onError = { failed = true }, onSuccess = { failed = false })
                        if (failed) Text("加载失败，点击查看原图", Modifier.align(Alignment.Center).padding(10.dp), style = MaterialTheme.typography.bodySmall)
                        if (selection != null) Icon(if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            if (checked) "已选择" else "未选择", Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .clip(RoundedCornerShape(50)).background(if (checked) LogoYellow else MaterialTheme.colorScheme.surface),
                            tint = if (checked) Ink else MaterialTheme.colorScheme.onSurface)
                    }
                    if (selection == null) {
                        Text(image.source?.title?.ifBlank { "来源帖子" } ?: image.alt.ifBlank { "收藏图片" },
                            Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (image.source != null && source != null) Text("原帖 · ${image.source.postNumber} 楼",
                                Modifier.weight(1f).clickable(role = Role.Button) { source(image.source) }.heightIn(min = 48.dp)
                                    .padding(horizontal = 10.dp, vertical = 14.dp).testTag("library-source-${image.id}"),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Spacer(Modifier.weight(1f))
                            if (remove != null) CommunityIconButton(Icons.Rounded.DeleteOutline, "移除收藏图片", { remove(image) }, Modifier.testTag("library-remove-${image.id}"))
                        }
                    }
                }
            }
        }
    }
}
