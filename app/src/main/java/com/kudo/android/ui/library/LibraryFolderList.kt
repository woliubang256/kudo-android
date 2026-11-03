package com.kudo.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kudo.android.data.DEFAULT_IMAGE_FOLDER
import com.kudo.android.data.ImageFolder
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.isActive
import kotlin.math.abs

@Composable
internal fun LibraryFolderList(folders: List<ImageFolder>, enabled: Boolean, resetKey: Int,
    saveOrder: (List<String>) -> Unit, edit: (ImageFolder) -> Unit, delete: (ImageFolder) -> Unit) {
    val list = rememberLazyListState()
    val drag = remember(list) { FolderDragState(list) }
    var draft by remember(folders, resetKey) { mutableStateOf(folders) }
    val currentDraft by rememberUpdatedState(draft)
    val currentSave by rememberUpdatedState(saveOrder)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val maxHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() * .45f }.coerceAtLeast(48.dp)
    val edge = with(density) { 48.dp.toPx() }
    val speed = with(density) { 480.dp.toPx() }
    fun reorder() {
        val reordered = drag.reordered(currentDraft)
        if (reordered != currentDraft) {
            val first = list.firstVisibleItemIndex
            val offset = list.firstVisibleItemScrollOffset
            draft = reordered
            list.requestScrollToItem(first, offset)
        }
    }
    LaunchedEffect(folders, resetKey) { drag.stop() }
    LaunchedEffect(drag.id) {
        var previous = withFrameNanos { it }
        while (isActive && drag.id != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, .032f)
            previous = now
            reorder()
            val scroll = drag.edgeScroll(edge) * speed * seconds
            if (scroll != 0f) list.scrollBy(scroll)
        }
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("library-folder-list"), state = list) {
        itemsIndexed(draft, key = { _, folder -> folder.id }) { index, folder ->
            val canAct = enabled && drag.id == null
            val actions = buildList {
                fun move(to: Int): Boolean {
                    if (!canAct) return false
                    val reordered = currentDraft.toMutableList().apply { add(to, removeAt(index)) }
                    draft = reordered
                    currentSave(reordered.map(ImageFolder::id))
                    return true
                }
                if (index > 0) add(CustomAccessibilityAction("上移") { move(index - 1) })
                if (index < draft.lastIndex) add(CustomAccessibilityAction("下移") { move(index + 1) })
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .zIndex(if (drag.id == folder.id) 1f else 0f)
                .graphicsLayer { translationY = drag.translation(folder.id) }
                .background(if (drag.id == folder.id) LogoYellow.copy(alpha = .18f) else androidx.compose.ui.graphics.Color.Transparent)
                .testTag("library-folder-row-${folder.id}"), verticalAlignment = Alignment.CenterVertically) {
                Text(folder.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (folder.id != DEFAULT_IMAGE_FOLDER) {
                    CommunityIconButton(Icons.Rounded.Edit, "重命名${folder.name}", { edit(folder) }, enabled = canAct)
                    CommunityIconButton(Icons.Rounded.DeleteOutline, "删除${folder.name}", { delete(folder) }, enabled = canAct)
                }
                Box(Modifier.size(48.dp).semantics {
                    contentDescription = "调整${folder.name}顺序"
                    stateDescription = "第 ${index + 1} 项，共 ${draft.size} 项"
                    customActions = actions
                    if (!enabled) disabled()
                }.testTag("library-folder-drag-${folder.id}")
                    .pointerInput(folder.id, enabled, folders, resetKey) {
                        if (enabled && folders.size > 1) detectDragGesturesAfterLongPress(
                            onDragStart = { if (drag.start(folder.id)) haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDrag = { change, amount -> change.consume(); drag.dragBy(amount.y); reorder() },
                            onDragEnd = {
                                val sorted = drag.reordered(currentDraft)
                                draft = sorted
                                drag.stop()
                                if (sorted.map(ImageFolder::id) != folders.map(ImageFolder::id)) currentSave(sorted.map(ImageFolder::id))
                            },
                            onDragCancel = { if (drag.id != null) { drag.stop(); draft = folders } },
                        )
                    }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private class FolderDragState(private val list: LazyListState) {
    var id by mutableStateOf<String?>(null)
        private set
    private var top by mutableFloatStateOf(0f)
    private var height by mutableIntStateOf(0)
    fun start(key: String): Boolean {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
        top = item.offset.toFloat(); height = item.size; id = key
        return true
    }
    fun stop() { id = null }
    fun dragBy(delta: Float) { if (id != null) top += delta }
    fun translation(key: String): Float = if (id == key)
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { top - it.offset } ?: 0f else 0f
    fun reordered(folders: List<ImageFolder>): List<ImageFolder> {
        val current = folders.indexOfFirst { it.id == id }
        if (current < 0) return folders
        val center = top + height / 2f
        val target = list.layoutInfo.visibleItemsInfo.filter {
            it.index > current && center > it.offset + it.size / 2f || it.index < current && center < it.offset + it.size / 2f
        }.minByOrNull { abs(center - it.offset - it.size / 2f) }?.index ?: return folders
        return folders.toMutableList().apply { add(target, removeAt(current)) }
    }
    fun edgeScroll(edge: Float): Float {
        val center = top + height / 2f
        val start = maxOf(0, list.layoutInfo.viewportStartOffset)
        val end = list.layoutInfo.viewportEndOffset
        return when {
            center < start + edge && list.canScrollBackward -> ((center - start - edge) / edge).coerceIn(-1f, 0f)
            center > end - edge && list.canScrollForward -> ((center - end + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}
