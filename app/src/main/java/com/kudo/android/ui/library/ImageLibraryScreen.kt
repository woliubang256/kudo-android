package com.kudo.android.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CreateNewFolder
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
import com.kudo.android.ui.editor.EditorToolBackdropHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ImageLibraryScreen(back: () -> Unit, source: (ImageSource) -> Unit) {
    key(LocalImageLibrary.current) {
        EditorToolBackdropHost { ImageLibraryContent(back, source) }
    }
}

@Composable
private fun ImageLibraryContent(back: () -> Unit, source: (ImageSource) -> Unit) {
    val state = LocalImageLibrary.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var manage by remember(state) { mutableStateOf(false) }
    var removing by remember(state) { mutableStateOf<SavedImage?>(null) }
    var changingFolder by remember(state) { mutableStateOf<SavedImage?>(null) }
    val library = state?.library
    if (library != null && state.error == null) {
        var folderId by rememberSaveable(state.accountId) { mutableStateOf(library.folders.firstOrNull()?.id) }
        LaunchedEffect(library, folderId) { if (folderId != null && library.folders.none { it.id == folderId }) folderId = null }
        ImageLibraryBrowser(library, folderId, { folderId = it }, modifier = Modifier.fillMaxSize().testTag("image-library-screen"), source = source, remove = { removing = it },
            changeFolder = { changingFolder = it }, header = {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back)
                Text("图库管理", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                CommunityIconButton(Icons.Rounded.CreateNewFolder, "管理图库目录", { manage = true })
            }
        })
    } else MaterialPageScaffold(modifier = Modifier.testTag("image-library-screen"), header = {
        AccountPageTopBar("图库管理", back) {
            CommunityIconButton(Icons.Rounded.CreateNewFolder, "管理图库目录", { manage = true }, enabled = library != null && state.error == null)
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state?.error != null) {
                Text(state.error!!, Modifier.padding(16.dp))
                CommunityButton("重试", { scope.launch { state.reload() } }, Modifier.padding(horizontal = 16.dp))
            } else if (library == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CommunityLoadingIndicator() }
        }
    }
    if (manage && state != null) LibraryFolderSheet(state) { manage = false }
    changingFolder?.let { image -> CollectImageSheet(image, move = true) { changingFolder = null } }
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
    var sortRevision by remember { mutableIntStateOf(0) }
    EditorToolSheet("管理图库目录", dismiss) { close ->
        fun update(closeAfter: Boolean = true, transform: (ImageLibrary) -> ImageLibrary) {
            busy = true
            error = null
            scope.launch {
                try { state.update(transform); if (closeAfter) close() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { sortRevision++; error = failure.message ?: "目录保存失败，请重试" }
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
            LibraryFolderList(state.library?.folders.orEmpty(), !busy, sortRevision,
                saveOrder = { ids -> update(closeAfter = false) { it.reorderFolders(ids) } },
                edit = { editing = it.id; name = it.name }, delete = { deleting = it.id })
            CommunityButton("新建目录", { creating = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
                enabled = !busy, compact = true)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
