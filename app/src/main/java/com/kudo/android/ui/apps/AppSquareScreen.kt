package com.kudo.android.ui.apps

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.kudo.android.data.MiniAppEntry
import com.kudo.android.data.MiniAppSort
import com.kudo.android.ui.components.*
import com.kudo.android.ui.editor.EditorToolBackdropHost
import com.kudo.android.ui.editor.EditorToolSheet
import com.kudo.android.ui.theme.LogoYellow

@Composable
internal fun AppSquareScreen(state: AppSquareState, search: (String) -> Unit, retry: () -> Unit,
    more: () -> Unit, back: () -> Unit, login: () -> Unit, open: (String) -> Unit,
    initialGrid: Boolean = true, saveGrid: (Boolean) -> Unit = {}, sort: (MiniAppSort) -> Unit = {},
    showAbout: (MiniAppEntry) -> Unit = {}, dismissAbout: () -> Unit = {}, retryAbout: () -> Unit = {},
    loggedIn: Boolean = false) {
    EditorToolBackdropHost {
        AppSquareContent(state, search, retry, more, back, login, open, initialGrid, saveGrid,
            sort, showAbout, dismissAbout, retryAbout, loggedIn)
    }
}

@Composable
private fun AppSquareContent(state: AppSquareState, search: (String) -> Unit, retry: () -> Unit,
    more: () -> Unit, back: () -> Unit, login: () -> Unit, open: (String) -> Unit,
    initialGrid: Boolean, saveGrid: (Boolean) -> Unit, sort: (MiniAppSort) -> Unit,
    showAbout: (MiniAppEntry) -> Unit, dismissAbout: () -> Unit, retryAbout: () -> Unit, loggedIn: Boolean) {
    var grid by rememberSaveable { mutableStateOf(initialGrid) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun navigateBack() {
        if (expanded) { expanded = false; focus.clearFocus(); keyboard?.hide() }
        else back()
    }
    BackHandler(expanded) { navigateBack() }
    MaterialPageScaffold(header = {
        AppSquareToolbar(state.query, expanded, grid, ::navigateBack, { expanded = true }, search,
            { grid = !grid; saveGrid(grid) }, {
                focus.clearFocus(); keyboard?.hide(); filtering = true
            })
    }) { padding ->
        // Both layouts share one lazy grid, keeping the visible app when switching layouts.
        val scroll = rememberLazyGridState()
        var appliedRevision by rememberSaveable { mutableIntStateOf(state.revision) }
        LaunchedEffect(state.revision) {
            if (appliedRevision != state.revision) { scroll.scrollToItem(0); appliedRevision = state.revision }
        }
        val nearEnd by remember { derivedStateOf {
            val info = scroll.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 4
        } }
        LaunchedEffect(nearEnd, state.page, state.loading, state.hasMore, state.moreError) {
            if (nearEnd && !state.loading && state.hasMore && state.moreError == null) more()
        }
        LazyVerticalGrid(if (grid) GridCells.Adaptive(96.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)) else GridCells.Fixed(1),
            Modifier.fillMaxSize().imePadding().navigationBarsPadding().testTag(if (grid) "apps-grid" else "apps-list"),
            state = scroll, contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(if (grid) 16.dp else 4.dp)) {
            items(state.apps, key = MiniAppEntry::id) { app ->
                AppSquareItem(app, grid, { open(app.url) }, {
                    focus.clearFocus(); keyboard?.hide(); showAbout(app)
                })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    state.loading -> LoadingRow()
                    state.error != null -> ErrorState(state.error, retry, login)
                    state.moreError != null -> ErrorState(state.moreError, more, login)
                    state.apps.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text(when {
                            state.query.isNotBlank() -> "没有找到应用"
                            state.sort == MiniAppSort.FAVORITES -> "暂无收藏的应用"
                            else -> "暂无应用"
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (filtering) EditorToolSheet("筛选应用", { filtering = false }) { close ->
        MiniAppSort.entries.filter { loggedIn || !it.favorite }.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = {
                close(); sort(option)
            }).semantics { selected = state.sort == option }.heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp).testTag("apps-sort-${option.key}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(option.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (state.sort == option) Icon(Icons.Rounded.Check, null, Modifier.size(22.dp), tint = LogoYellow)
            }
        }
    }
    state.about?.let { about ->
        EditorToolSheet("关于应用", dismissAbout, showTitle = false) { close ->
            when {
                about.loading -> LoadingRow()
                about.info != null -> MiniAppAboutContent(about.info)
                about.error != null -> {
                    Text("应用信息加载失败", style = MaterialTheme.typography.bodyMedium)
                    CommunityButton("重试", retryAbout, Modifier.fillMaxWidth())
                }
            }
            CommunityButton("关闭", close, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AppSquareItem(app: MiniAppEntry, grid: Boolean, open: () -> Unit, about: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .combinedClickable(role = Role.Button, onClick = open, onLongClickLabel = "关于应用", onLongClick = about)
        .testTag("app-${app.id}").padding(horizontal = 8.dp, vertical = if (grid) 8.dp else 10.dp)
    if (grid) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppIcon(app, Modifier.size(60.dp))
        Text(app.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
    } else BoxWithConstraints(modifier) {
        val statisticsWidth = maxWidth * .45f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(app, Modifier.size(48.dp))
            Text(app.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AppSquareStatistics(app, Modifier.widthIn(max = statisticsWidth))
        }
    }
}

@Composable
private fun AppIcon(app: MiniAppEntry, modifier: Modifier) {
    SubcomposeAsyncImage(app.iconUrl, null, modifier.clip(RoundedCornerShape(14.dp)),
        loading = { Icon(Icons.Rounded.Apps, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        error = { Icon(Icons.Rounded.Apps, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
}
