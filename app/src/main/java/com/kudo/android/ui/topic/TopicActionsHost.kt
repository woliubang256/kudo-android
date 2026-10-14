package com.kudo.android.ui.topic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.components.liquid.*
import kotlinx.coroutines.launch

class TopicActionsController(val openList: (Int) -> Unit, val openDetail: (Int, Post?, (Post) -> Unit) -> Unit,
    val openReply: (Int, Post, (Post) -> Unit) -> Unit = { id, post, bookmark -> openDetail(id, post, bookmark) },
    val edit: (Int, Post) -> Unit = { _, _ -> }, val history: (Int, Post) -> Unit = { _, _ -> })
val LocalTopicActions = staticCompositionLocalOf { TopicActionsController({}, { _, _, _ -> }) }
val LocalIgnoredUsers = staticCompositionLocalOf<Set<String>> { emptySet() }

@Composable
fun TopicActionsHost(repository: CommunityRepository, viewer: Member?, glass: GlassMode, login: () -> Unit,
    ignored: (Int, String) -> Unit, message: suspend (String) -> Unit, changed: () -> Unit = {},
    editPost: (Int, Post) -> Unit = { _, _ -> }, openHistory: ((Int, Post) -> Unit)? = null, content: @Composable () -> Unit) {
    val vm: TopicActionsViewModel = viewModel(key = "topic-actions-${viewer?.id}", factory = viewModelFactory { initializer { TopicActionsViewModel(repository, viewer) } })
    val current = vm.state
    var retained by remember(vm) { mutableStateOf(current) }
    SideEffect { if (current.topicId != null) retained = current }
    val state = if (current.topicId != null) current else retained
    val context = LocalContext.current
    val backdrop = rememberLayerBackdrop()
    val visibility = remember(vm) { MutableTransitionState(false) }
    var detailBookmark by remember(vm) { mutableStateOf<((Post) -> Unit)?>(null) }
    val currentEdit by rememberUpdatedState(editPost)
    val currentHistory by rememberUpdatedState(openHistory)
    LaunchedEffect(current.topicId) { visibility.targetState = current.topicId != null }
    LaunchedEffect(vm) { vm.events.collect { event -> when (event) {
        is TopicActionEvent.Message -> launch { message(event.text) }
        is TopicActionEvent.Ignored -> ignored(event.viewerId, event.username)
        TopicActionEvent.Refresh -> changed()
    } } }
    val controller = remember(vm) { TopicActionsController({ detailBookmark = null; vm.open(it) }, { id, post, bookmark ->
        detailBookmark = bookmark; vm.open(id, true, post)
    }, { id, post, bookmark ->
        detailBookmark = bookmark; vm.open(id, true, post, isReply = true)
    }, { id, post -> currentEdit(id, post) }, { id, post -> currentHistory?.invoke(id, post) }) }
    fun authenticate(action: () -> Unit) { if (viewer == null) { vm.dismiss(); login() } else action() }
    val showing = current.topicId != null || visibility.currentState || visibility.targetState
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
            .then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (showing) cancelFocusChange() } }.focusGroup()) {
            CompositionLocalProvider(LocalTopicActions provides controller,
                LocalReadingBlocked provides (LocalReadingBlocked.current || showing),
                LocalIgnoredUsers provides viewer?.ignoredUsers.orEmpty().map { it.lowercase(java.util.Locale.ROOT) }.toSet(), content = content)
        }
        val title = when (state.panel) {
            TopicActionPanel.MENU -> if (state.isReply) "回复操作" else "主题操作"; TopicActionPanel.HISTORY -> "编辑历史"
            TopicActionPanel.REPORT -> "举报"; TopicActionPanel.IGNORE -> "忽略用户"
        }
        if (state.panel == TopicActionPanel.REPORT) {
            AnimatedVisibility(visibility, enter = fadeIn(), exit = fadeOut()) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Box(Modifier.fillMaxSize().focusRequester(focus).focusProperties { onExit = { if (visibility.targetState) cancelFocusChange() } }
                    .focusGroup().semantics { dialog(); paneTitle = "举报" }) {
                    TopicReportPage(state, vm::dismiss, vm::chooseFlag, vm::message, vm::confirm, vm::submitReport, vm::report, visibility.targetState)
                }
            }
        } else LiquidModal(visibility, title, true, backdrop, glass, vm::dismiss,
            dismissEnabled = !state.busy,
            showTitle = false,
            showActions = state.panel == TopicActionPanel.MENU || state.panel == TopicActionPanel.IGNORE,
            actions = {
                if (state.panel == TopicActionPanel.MENU) LiquidModalAction("取消", vm::dismiss, Modifier.fillMaxWidth())
                if (state.panel == TopicActionPanel.IGNORE) {
                    CommunityButton(if (state.busy) "提交中…" else "确认忽略", vm::submitIgnore, enabled = state.duration != null && !state.busy && !state.writeLocked && viewer?.canIgnoreUsers == true && state.post?.author?.id != viewer.id,
                        modifier = Modifier.weight(1f))
                }
            }) {
            when (state.panel) {
                TopicActionPanel.MENU -> {
                    TopicActionMenu(state.post, state.fromDetail, visibility.targetState && !state.busy && !state.writeLocked,
                        copy = { state.post?.let { post ->
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("正文", plainText(post.cooked)))
                            vm.dismiss()
                        } }, bookmark = { authenticate {
                            val post = state.post
                            if (post != null && state.fromDetail && detailBookmark != null) { vm.dismiss(); detailBookmark?.invoke(post) } else vm.bookmark()
                        } }, history = {
                            val post = state.post; val id = state.topicId
                            if (currentHistory != null && post != null && id != null) { vm.dismiss(); currentHistory?.invoke(id, post) }
                            else vm.history()
                        }, ignore = { authenticate { vm.ignore() } }, report = { authenticate { vm.report() } },
                        copyLink = { state.link?.let { link ->
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("链接", link))
                            vm.dismiss()
                        } }, edit = if (state.post?.editableBy(viewer?.id) == true) ({
                            val post = state.post!!; val topicId = state.topicId!!
                            vm.dismiss(); currentEdit(topicId, post)
                        }) else null)
                    if (state.loading) LoadingRow()
                }
                TopicActionPanel.HISTORY -> TopicHistoryContent(state, vm::history)
                TopicActionPanel.REPORT -> TopicReportContent(state, vm::chooseFlag, vm::message, vm::confirm)
                TopicActionPanel.IGNORE -> TopicIgnoreContent(state, viewer, vm::duration)
            }
            state.error?.let { error ->
                Text(error.message ?: "操作失败，请稍后重试", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (!state.busy && !state.writeLocked && (state.panel == TopicActionPanel.HISTORY || state.panel == TopicActionPanel.MENU || (state.panel == TopicActionPanel.REPORT && state.flags == null))) {
                    TextButton(onClick = { when (state.panel) { TopicActionPanel.HISTORY -> vm.history(state.revision?.current); TopicActionPanel.REPORT -> vm.report(); else -> vm.loadPost() } }) { Text("重试") }
                }
            }
            if (state.writeLocked) Text("上次提交结果尚未确认，请在网站核对后再操作。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TopicActionMenu(post: Post?, fromDetail: Boolean, enabled: Boolean, copy: () -> Unit, bookmark: () -> Unit,
    history: () -> Unit, ignore: () -> Unit, report: () -> Unit, copyLink: () -> Unit = {}, edit: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp).testTag("topic-action-row"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (fromDetail) TopicCircleAction("复制", Icons.Rounded.ContentCopy, enabled && post != null, copy)
        if (fromDetail) TopicCircleAction("复制链接", Icons.Rounded.Link, enabled && post != null, copyLink)
        TopicCircleAction(if (post?.bookmarkId != null) "取消收藏" else "收藏", if (post?.bookmarkId != null) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, enabled && post != null, bookmark)
        edit?.let { TopicCircleAction("编辑", Icons.Rounded.Edit, enabled, it) }
        if (post?.hasEditHistory == true) TopicCircleAction("编辑历史", Icons.Rounded.History, enabled, history)
        if (!fromDetail) TopicCircleAction("屏蔽", Icons.Rounded.VisibilityOff, enabled && post != null, ignore)
        TopicCircleAction("举报", Icons.Rounded.Flag, enabled && post != null, report)
    }
}

@Composable
private fun TopicCircleAction(label: String, icon: ImageVector, enabled: Boolean, action: () -> Unit) {
    Column(Modifier.widthIn(min = 72.dp).clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, role = Role.Button, onClick = action)
        .padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.85f else 0.3f))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.85f else 0.3f))
    }
}
