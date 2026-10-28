package com.kudo.android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kudo.android.data.SavedImage
import com.kudo.android.ui.components.*
import com.kudo.android.ui.editor.EditorToolSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun CollectImageSheet(image: SavedImage, dismiss: () -> Unit) {
    val state = LocalImageLibrary.current ?: return
    key(state) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var busy by remember { mutableStateOf(false) }
        var creating by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        EditorToolSheet("收藏图片", { dismiss() }) { close ->
            Text("选择收藏目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.error != null) {
                Text(state.error!!)
                CommunityButton("重试", { scope.launch { state.reload() } })
            } else if (state.library == null) CommunityLoadingIndicator(Modifier.padding(16.dp))
            state.library?.folders?.forEach { folder ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button) {
                    busy = true
                    scope.launch {
                        try {
                            val duplicate = state.library?.images?.any { it.folderId == folder.id && it.originalUrl == image.originalUrl } == true
                            state.update { it.save(image.copy(folderId = folder.id)) }
                            showKudoToast(context, if (duplicate) "此目录已收藏该图片" else "已收藏到${folder.name}")
                            close()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "收藏失败，请重试" }
                        finally { busy = false }
                    }
                }.heightIn(min = 56.dp).padding(horizontal = 12.dp).testTag("collect-folder-${folder.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Folder, null)
                    Text(folder.name, Modifier.weight(1f))
                    Text((state.library?.images?.count { it.folderId == folder.id } ?: 0).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button) { creating = !creating }
                .heightIn(min = 52.dp).padding(horizontal = 12.dp).testTag("collect-new-folder"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null)
                Text("新建目录", Modifier.padding(start = 12.dp))
            }
            if (creating) {
                CommunityTextField(name, { name = it; error = null }, Modifier.fillMaxWidth().testTag("collect-folder-name"), placeholder = "目录名称", singleLine = true, enabled = !busy)
                CommunityButton("新建并收藏", {
                    busy = true
                    scope.launch {
                        try {
                            val id = UUID.randomUUID().toString()
                            state.update { it.addFolder(name, id).save(image.copy(folderId = id)) }
                            showKudoToast(context, "已收藏到${name.trim()}")
                            close()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "收藏失败，请重试" }
                        finally { busy = false }
                    }
                }, enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
