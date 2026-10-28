package com.kudo.android.ui.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kudo.android.data.ApiFailure
import com.kudo.android.data.FailureKind
import com.kudo.android.data.HomeTab
import com.kudo.android.data.HomeTabType
import com.kudo.android.ui.account.LocalWebVerification
import com.kudo.android.ui.components.CommunityButton
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.CommunityLoadingIndicator
import com.kudo.android.ui.components.CommunityTextField
import com.kudo.android.ui.components.MaterialPageScaffold
import com.kudo.android.ui.components.PageTopBar
import com.kudo.android.ui.components.TopBarTextButton
import com.kudo.android.ui.components.liquid.LiquidModal
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.abs
import kotlinx.coroutines.isActive

@Composable
internal fun HomeTabsEditorScreen(
    state: HomeTabsEditorState,
    move: (String, Int) -> Unit,
    add: (HomeTab) -> Unit,
    remove: (String) -> Unit,
    restore: () -> Unit,
    query: (String) -> Unit,
    source: (HomeTabType) -> Unit,
    retry: () -> Unit,
    save: () -> Unit,
    back: () -> Unit,
    login: () -> Unit,
) {
    val list = key(state.accountId) { rememberLazyListState() }
    val drag = remember(state.accountId, list) { HomeTabsEditorDragState(list) }
    val currentState by rememberUpdatedState(state)
    val currentMove by rememberUpdatedState(move)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val backdrop = rememberLayerBackdrop()
    val leaving = remember(state.accountId) { MutableTransitionState(false) }
    val showingModal = leaving.currentState || leaving.targetState
    val dragging = drag.key != null
    val canSave = state.dirty && state.tabs.isNotEmpty() && !dragging && !showingModal
    val candidates = state.candidates
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val scrollSpeed = with(LocalDensity.current) { 640.dp.toPx() }

    fun requestBack() {
        drag.stop()
        focus.clearFocus()
        keyboard?.hide()
        if (state.dirty) leaving.targetState = true else back()
    }

    BackHandler(onBack = ::requestBack)
    LaunchedEffect(state.dirty) {
        if (!state.dirty) leaving.targetState = false
    }
    LaunchedEffect(drag.key) {
        val draggedKey = drag.key ?: return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isActive && drag.key == draggedKey) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .032f)
            previousFrame = frame
            if (currentState.tabs.none { it.key == draggedKey }) {
                drag.stop()
                break
            }
            drag.reorder(currentState.tabs, currentMove)
            val scroll = drag.edgeScroll(currentState.tabs, edge) * scrollSpeed * seconds
            if (scroll != 0f) list.scrollBy(scroll)
        }
    }

    Box(Modifier.fillMaxSize().testTag("home-tabs-editor")) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).semantics {
            if (showingModal) hideFromAccessibility()
        }) {
            MaterialPageScaffold(header = {
                PageTopBar("编辑首页", ::requestBack) {
                    TopBarTextButton("保存", {
                        focus.clearFocus()
                        keyboard?.hide()
                        if (canSave) save()
                    }, Modifier.testTag("home-tabs-save").semantics { if (!canSave) disabled() }, enabled = canSave)
                }
            }) { padding ->
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().padding(padding)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .imePadding().testTag("home-tabs-list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    userScrollEnabled = !dragging && !showingModal,
                ) {
                    item(key = "editor-introduction") {
                        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (state.accountId == null) "定制访客首页" else "定制当前账号的首页",
                                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text("长按手柄拖动排序，修改只在保存后生效。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item(key = "editor-selected-heading") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("已添加 · ${state.tabs.size}", Modifier.weight(1f).semantics { heading() },
                                style = MaterialTheme.typography.titleSmall)
                            HomeTabsEditorTextAction("恢复默认", restore,
                                Modifier.testTag("home-tabs-restore"), enabled = !dragging && !showingModal)
                        }
                        if (state.tabs.size == 1) Text("至少保留一个首页标签", Modifier.padding(bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    itemsIndexed(state.tabs, key = { _, tab -> selectedItemKey(tab.key) }) { index, tab ->
                        val actions = buildList {
                            if (index > 0) this.add(CustomAccessibilityAction("上移") {
                                val position = currentState.tabs.indexOfFirst { it.key == tab.key }
                                if (position > 0 && drag.key == null) {
                                    currentMove(tab.key, position - 1)
                                    true
                                } else false
                            })
                            if (index < state.tabs.lastIndex) this.add(CustomAccessibilityAction("下移") {
                                val position = currentState.tabs.indexOfFirst { it.key == tab.key }
                                if (position >= 0 && position < currentState.tabs.lastIndex && drag.key == null) {
                                    currentMove(tab.key, position + 1)
                                    true
                                } else false
                            })
                        }
                        HomeTabsEditorSelectedRow(
                            tab = tab,
                            index = index,
                            count = state.tabs.size,
                            actions = actions,
                            remove = { if (state.tabs.size > 1 && !dragging) remove(tab.key) },
                            removeEnabled = state.tabs.size > 1 && !dragging && !showingModal,
                            modifier = Modifier.zIndex(if (drag.key == tab.key) 1f else 0f)
                                .graphicsLayer { translationY = drag.translation(tab.key) }
                                .background(if (drag.key == tab.key) LogoYellow.copy(alpha = .18f) else MaterialTheme.colorScheme.background)
                                .testTag("home-tabs-selected-${tab.key}"),
                            handle = Modifier.pointerInput(tab.key, drag, state.tabs.size > 1) {
                                if (state.tabs.size > 1) detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        focus.clearFocus()
                                        keyboard?.hide()
                                        if (drag.start(tab.key)) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = {
                                        drag.reorder(currentState.tabs, currentMove)
                                        drag.stop()
                                    },
                                    onDragCancel = drag::stop,
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag.dragBy(amount.y)
                                        drag.reorder(currentState.tabs, currentMove)
                                    },
                                )
                            },
                        )
                    }
                    item(key = "editor-candidates-heading") {
                        Text("添加首页标签", Modifier.padding(top = 28.dp, bottom = 12.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleSmall)
                        HomeTabsEditorSources(state.source, source)
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f))
                            .heightIn(min = 52.dp).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            CommunityTextField(state.query, query, Modifier.weight(1f).padding(vertical = 12.dp)
                                .testTag("home-tabs-query"), placeholder = when (state.source) {
                                    HomeTabType.CATEGORY -> "搜索一级分类"
                                    HomeTabType.TAG -> "搜索标签"
                                    else -> "搜索内置项"
                                }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                }))
                            if (state.query.isNotEmpty()) CommunityIconButton(Icons.Rounded.Close, "清空搜索", { query("") },
                                Modifier.testTag("home-tabs-clear-query"))
                            else Spacer(Modifier.width(12.dp))
                        }
                        if (state.source == HomeTabType.CATEGORY) Text("仅显示一级分类", Modifier.padding(bottom = 12.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.source == HomeTabType.TAG && state.query.isBlank()) {
                        item(key = "editor-tag-search-hint") {
                            HomeTabsEditorHint("输入关键词搜索标签", "home-tabs-tag-search-hint")
                        }
                    } else {
                        if (state.loading) item(key = "editor-loading") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp).testTag("home-tabs-loading"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CommunityLoadingIndicator()
                                Text("正在加载…", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        state.error?.let { error ->
                            item(key = "editor-error") { HomeTabsEditorError(error, retry, login) }
                        }
                        if (!state.loading && state.error == null && candidates.isEmpty()) item(key = "editor-empty") {
                            HomeTabsEditorHint(if (state.query.isBlank()) "暂无可添加的内容" else "没有找到匹配项，试试其他关键词",
                                "home-tabs-empty")
                        }
                        items(candidates, key = { "candidate-${it.key}" }) { tab ->
                            val added = state.tabs.any { sameEditorTab(it, tab) }
                            HomeTabsEditorCandidateRow(tab, added, !dragging && !showingModal) {
                                if (currentState.tabs.none { sameEditorTab(it, tab) } && drag.key == null) add(tab)
                            }
                        }
                    }
                    item(key = "editor-bottom-space") { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
        LiquidModal(leaving, "放弃未保存的修改？", false, backdrop, LocalDisplaySettings.current.glass,
            dismiss = { leaving.targetState = false }, showActions = false) {
            Text("你的修改还未保存，离开后不会保留。", style = MaterialTheme.typography.bodyMedium)
            CommunityButton("继续编辑", { leaving.targetState = false },
                Modifier.fillMaxWidth().testTag("home-tabs-keep-editing"))
            HomeTabsEditorTextAction("放弃修改并离开", {
                leaving.targetState = false
                back()
            }, Modifier.fillMaxWidth().testTag("home-tabs-discard"))
        }
    }
}

@Composable
private fun HomeTabsEditorSelectedRow(
    tab: HomeTab,
    index: Int,
    count: Int,
    actions: List<CustomAccessibilityAction>,
    remove: () -> Unit,
    removeEnabled: Boolean,
    modifier: Modifier,
    handle: Modifier,
) {
    Column(modifier.fillMaxWidth().semantics {
        customActions = actions
        stateDescription = "第 ${index + 1} 项，共 $count 项"
    }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(handle.size(48.dp).semantics {
                contentDescription = "长按拖动${tab.label}排序"
                customActions = actions
            }.focusable().testTag("home-tabs-drag-${tab.key}"), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragHandle, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HomeTabsEditorTabLabel(tab, Modifier.weight(1f).padding(vertical = 12.dp))
            CommunityIconButton(Icons.Rounded.Close, "移除${tab.label}", remove,
                Modifier.testTag("home-tabs-remove-${tab.key}"), enabled = removeEnabled)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
    }
}

@Composable
private fun HomeTabsEditorCandidateRow(tab: HomeTab, added: Boolean, enabled: Boolean, add: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeTabsEditorTabLabel(tab, Modifier.weight(1f).padding(vertical = 12.dp))
            CommunityButton(if (added) "已添加" else "添加", add,
                Modifier.heightIn(min = 48.dp).testTag("home-tabs-add-${tab.key}").semantics {
                    stateDescription = if (added) "已添加" else "未添加"
                }, enabled = enabled && !added, compact = true)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
    }
}

@Composable
private fun HomeTabsEditorTabLabel(tab: HomeTab, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(tab.label, style = MaterialTheme.typography.bodyLarge)
        Text(when (tab.type) {
            HomeTabType.FILTER -> "内置筛选"
            HomeTabType.DIRECTORY -> "分类目录"
            HomeTabType.CATEGORY -> "一级分类"
            HomeTabType.TAG -> "标签"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeTabsEditorSources(source: HomeTabType, change: (HomeTabType) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(HomeTabType.FILTER to "内置", HomeTabType.CATEGORY to "分类", HomeTabType.TAG to "标签").forEach { (type, title) ->
            val selected = source == type || type == HomeTabType.FILTER && source == HomeTabType.DIRECTORY
            Box(Modifier.widthIn(min = 72.dp).heightIn(min = 48.dp)
                .background(if (selected) LogoYellow else Color.Transparent)
                .selectable(selected, role = Role.Tab, onClick = { change(type) })
                .padding(horizontal = 18.dp, vertical = 12.dp).testTag("home-tabs-source-${type.name}"),
                contentAlignment = Alignment.Center) {
                Text(title, color = if (selected) Ink else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun HomeTabsEditorTextAction(text: String, action: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = action)
        .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .75f else .3f))
    }
}

@Composable
private fun HomeTabsEditorHint(text: String, tag: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(tag),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun HomeTabsEditorError(error: Throwable, retry: () -> Unit, login: () -> Unit) {
    val verification = LocalWebVerification.current
    val kind = (error as? ApiFailure)?.kind
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("home-tabs-error"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(error.message?.takeIf { it.isNotBlank() } ?: "加载失败，请稍后重试", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CommunityButton(when (kind) {
            FailureKind.SIGN_IN -> "登录后重试"
            FailureKind.VERIFICATION -> "完成网页验证"
            else -> "重新加载"
        }, {
            when (kind) {
                FailureKind.SIGN_IN -> login()
                FailureKind.VERIFICATION -> if (verification != null) verification(retry) else login()
                else -> retry()
            }
        }, Modifier.testTag("home-tabs-retry"), compact = true)
    }
}

private fun selectedItemKey(key: String) = "home-tabs-selected-$key"

private fun sameEditorTab(selected: HomeTab, candidate: HomeTab): Boolean = selected.key == candidate.key ||
    selected.type == HomeTabType.TAG && candidate.type == HomeTabType.TAG &&
    (selected.value == candidate.value || selected.tagId != null && selected.tagId == candidate.tagId)

private class HomeTabsEditorDragState(private val list: LazyListState) {
    var key by mutableStateOf<String?>(null)
        private set
    private var top by mutableFloatStateOf(0f)
    private var height by mutableIntStateOf(0)

    fun start(tabKey: String): Boolean {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == selectedItemKey(tabKey) } ?: return false
        key = tabKey
        top = item.offset.toFloat()
        height = item.size
        return true
    }

    fun dragBy(amount: Float) {
        if (key != null) top += amount
    }

    fun stop() {
        key = null
    }

    fun translation(tabKey: String): Float {
        if (key != tabKey) return 0f
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == selectedItemKey(tabKey) } ?: return 0f
        return top - item.offset
    }

    fun reorder(tabs: List<HomeTab>, move: (String, Int) -> Unit) {
        val draggedKey = key ?: return
        val target = targetIndex(tabs) ?: return
        val firstIndex = list.firstVisibleItemIndex
        val firstOffset = list.firstVisibleItemScrollOffset
        move(draggedKey, target)
        list.requestScrollToItem(firstIndex, firstOffset)
    }

    private fun targetIndex(tabs: List<HomeTab>): Int? {
        val current = tabs.indexOfFirst { it.key == key }
        if (current < 0) return null
        val center = top + height / 2f
        val indices = tabs.mapIndexed { index, tab -> selectedItemKey(tab.key) to index }.toMap()
        val target = list.layoutInfo.visibleItemsInfo.filter { item ->
            val itemKey = item.key as? String ?: return@filter false
            val position = indices[itemKey] ?: return@filter false
            val midpoint = item.offset + item.size / 2f
            position > current && center > midpoint || position < current && center < midpoint
        }.minByOrNull { abs(center - (it.offset + it.size / 2f)) } ?: return null
        return indices[target.key as? String ?: return null]
    }

    fun edgeScroll(tabs: List<HomeTab>, edge: Float): Float {
        val current = tabs.indexOfFirst { it.key == key }
        if (current < 0) return 0f
        val layout = list.layoutInfo
        val start = maxOf(0, layout.viewportStartOffset)
        val end = layout.viewportEndOffset
        val center = top + height / 2f
        val first = layout.visibleItemsInfo.firstOrNull { it.key == selectedItemKey(tabs.first().key) }
        val last = layout.visibleItemsInfo.firstOrNull { it.key == selectedItemKey(tabs.last().key) }
        val canMoveUp = current > 0 || first != null && first.offset < start
        val canMoveDown = current < tabs.lastIndex || last != null && last.offset + last.size > end
        return when {
            center < start + edge && canMoveUp -> ((center - start - edge) / edge).coerceIn(-1f, 0f)
            center > end - edge && canMoveDown -> ((center - end + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
    }
}
