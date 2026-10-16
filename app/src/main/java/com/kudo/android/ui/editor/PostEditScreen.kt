package com.kudo.android.ui.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.components.liquid.LiquidModal

@Composable
fun PostEditScreen(vm: PostEditViewModel, glass: GlassMode, uploadImage: suspend (Uri) -> String, back: () -> Unit, login: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.upload { uploadImage(it) } }
    }
    val reset = remember { MutableTransitionState(false) }
    val backdrop = rememberLayerBackdrop()
    val showing = reset.currentState || reset.targetState
    BackHandler(vm.sending) { }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)) {
            val source = vm.source
            val draft = vm.draft
            if (source == null || draft == null || vm.loading) MaterialPageScaffold(header = { PageTopBar("编辑内容", back) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (vm.loading) LoadingRow()
                    else vm.error?.let { ErrorState(it, vm::load, login) }
                }
            } else EditorForm(Draft(title = draft.title, raw = draft.raw), emptyList(),
                edit = { vm.edit(draft.copy(title = it.title, raw = it.raw)) }, publish = vm::save,
                pickImage = { picker.launch("image/*") }, back = back,
                sending = vm.sending, uploading = vm.uploading, error = vm.error?.message,
                editing = true, editingReply = source.post.number != 1, canSave = vm.canSave,
                editReason = draft.reason, reasonChange = { vm.edit(draft.copy(reason = it)) }, imageSelection = vm::prepareImageUpload, extra = {
                    if (vm.conflict) Text("原文已有更新，你的修改已保留。请核对楼层后重新载入。",
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    if (vm.writeLocked || vm.error != null) CommunityButton("核对保存结果", vm::load,
                        enabled = !vm.sending && !vm.uploading, compact = true, modifier = Modifier.padding(horizontal = 20.dp))
                    if (vm.conflict) CommunityButton("重新载入原文", { reset.targetState = true },
                        enabled = !vm.sending && !vm.uploading, compact = true, modifier = Modifier.padding(horizontal = 20.dp))
                })
        }
        LiquidModal(reset, "重新载入原文", false, backdrop, glass, { reset.targetState = false }, actions = {
            CommunityButton("取消", { reset.targetState = false }, Modifier.weight(1f))
            CommunityButton("重新载入", { vm.useLatest(); reset.targetState = false }, Modifier.weight(1f))
        }) { Text("当前未保存的修改会被替换为最新原文。", style = MaterialTheme.typography.bodyMedium) }
    }
}
