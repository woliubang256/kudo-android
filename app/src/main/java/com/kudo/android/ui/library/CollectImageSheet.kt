package com.kudo.android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kudo.android.data.SavedImage
import com.kudo.android.ui.components.*
import com.kudo.android.ui.editor.EditorToolSheet
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun CollectImageSheet(image: SavedImage, move: Boolean = false, dismiss: () -> Unit) {
    val state = LocalImageLibrary.current ?: return
    key(state) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        var busy by remember { mutableStateOf(false) }
        var creating by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val currentFolder = if (move) state.library?.images?.firstOrNull { it.id == image.id }?.folderId else null
        EditorToolSheet(if (move) "修改分类" else "收藏图片", { dismiss() }) { close ->
            if (state.error != null) {
                Text(state.error!!)
                CommunityButton("重试", { scope.launch { state.reload() } })
            } else if (state.library == null) CommunityLoadingIndicator(Modifier.padding(16.dp))
            Column(Modifier.fillMaxWidth()) {
                state.library?.folders?.forEach { folder ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = if (move) Role.RadioButton else Role.Button) {
                        busy = true
                        scope.launch {
                            try {
                                val duplicate = state.library?.images?.any { it.folderId == folder.id && it.originalUrl == image.originalUrl } == true
                                state.update { if (move) it.moveImage(image.id, folder.id) else it.save(image.copy(folderId = folder.id)) }
                                showKudoToast(context, if (move) "已移至${folder.name}" else if (duplicate) "此目录已收藏该图片" else "已收藏到${folder.name}")
                                close()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: if (move) "分类修改失败，请重试" else "收藏失败，请重试" }
                            finally { busy = false }
                        }
                    }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { if (move) selected = folder.id == currentFolder }.testTag("collect-folder-${folder.id}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Folder, null)
                        Text(folder.name, Modifier.weight(1f))
                        if (folder.id == currentFolder) Icon(Icons.Rounded.Check, null, tint = LogoYellow)
                        Text((state.library?.images?.count { it.folderId == folder.id } ?: 0).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button) { creating = !creating }
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("collect-new-folder"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null)
                    Text("新建目录", Modifier.padding(start = 12.dp))
                }
            }
            if (creating) {
                CommunityTextField(name, { name = it; error = null }, Modifier.fillMaxWidth().testTag("collect-folder-name"), placeholder = "目录名称", singleLine = true, enabled = !busy)
                CommunityButton(if (move) "新建并移动" else "新建并收藏", {
                    busy = true
                    scope.launch {
                        try {
                            val id = UUID.randomUUID().toString()
                            state.update { library -> library.addFolder(name, id).let {
                                if (move) it.moveImage(image.id, id) else it.save(image.copy(folderId = id))
                            } }
                            showKudoToast(context, if (move) "已移至${name.trim()}" else "已收藏到${name.trim()}")
                            close()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: if (move) "分类修改失败，请重试" else "收藏失败，请重试" }
                        finally { busy = false }
                    }
                }, enabled = !busy && name.isNotBlank(), compact = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
