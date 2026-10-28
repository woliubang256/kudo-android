package com.kudo.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.account.AccountPageTopBar
import com.kudo.android.ui.components.*
import com.kudo.android.ui.editor.EditorToolSheet
import com.kudo.android.ui.topic.LocalImageGallery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ImageLibraryScreen(back: () -> Unit, source: (ImageSource) -> Unit) {
    val state = LocalImageLibrary.current
    val gallery = LocalImageGallery.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var folderId by rememberSaveable(state?.accountId) { mutableStateOf<String?>(null) }
    var manage by remember(state) { mutableStateOf(false) }
    var removing by remember(state) { mutableStateOf<SavedImage?>(null) }
    val library = state?.library
    LaunchedEffect(library, folderId) { if (folderId != null && library?.folders?.none { it.id == folderId } == true) folderId = null }
    MaterialPageScaffold(modifier = Modifier.testTag("image-library-screen"), header = {
        AccountPageTopBar("图库管理", back) {
            CommunityIconButton(Icons.Rounded.CreateNewFolder, "管理图库目录", { manage = true }, enabled = library != null && state.error == null)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state?.error != null) {
                Text(state.error!!, Modifier.padding(16.dp))
                CommunityButton("重试", { scope.launch { state.reload() } }, Modifier.padding(horizontal = 16.dp))
            } else if (library == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CommunityLoadingIndicator() }
            else ImageLibraryBrowser(library, folderId, { folderId = it }, { image ->
                gallery?.open(listOf(image.articleImage()), 0, "library-${image.id}", source = image.source)
            }, Modifier.weight(1f), source = source, remove = { removing = it })
        }
    }
    if (manage && state != null) LibraryFolderSheet(state) { manage = false }
    removing?.let { image ->
        var busy by remember(image) { mutableStateOf(false) }
        EditorToolSheet("移除收藏？", { removing = null }) { close ->
            Text("仅从本机图库移除，不删除原帖或原图。")
            CommunityButton("移除收藏", {
                busy = true
                scope.launch {
                    try { state?.update { it.removeImage(image.id) }; close() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { showKudoToast(context, failure.message ?: "移除失败，请重试") }
                    finally { busy = false }
                }
            }, enabled = !busy, compact = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp).testTag("library-confirm-remove"))
        }
    }
}

@Composable
private fun LibraryFolderSheet(state: ImageLibraryState, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    EditorToolSheet("管理图库目录", dismiss) { close ->
        fun update(transform: (ImageLibrary) -> ImageLibrary) {
            busy = true
            scope.launch {
                try { state.update(transform); close() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "目录保存失败，请重试" }
                finally { busy = false }
            }
        }
        if (deleting != null) {
            Text("删除目录后，里面的图片会移入默认收藏。")
            CommunityButton("删除目录", { update { it.deleteFolder(requireNotNull(deleting)) } }, enabled = !busy, compact = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp))
        } else if (creating || editing != null) {
            CommunityTextField(name, { name = it; error = null }, Modifier.fillMaxWidth().testTag("library-folder-name"), placeholder = "目录名称", singleLine = true, enabled = !busy)
            CommunityButton("保存目录", { update { library -> editing?.let { library.renameFolder(it, name) } ?: library.addFolder(name) } },
                enabled = !busy && name.isNotBlank(), compact = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp))
        } else {
            Column(Modifier.fillMaxWidth()) {
                state.library?.folders?.forEach { folder ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.name, Modifier.weight(1f))
                        if (folder.id != DEFAULT_IMAGE_FOLDER) {
                            CommunityIconButton(Icons.Rounded.Edit, "重命名${folder.name}", { editing = folder.id; name = folder.name }, enabled = !busy)
                            CommunityIconButton(Icons.Rounded.DeleteOutline, "删除${folder.name}", { deleting = folder.id }, enabled = !busy)
                        }
                    }
                }
            }
            CommunityButton("新建目录", { creating = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
                enabled = !busy, compact = true)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
