package com.kudo.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kudo.android.data.*
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.LocalToolbarActions
import com.kudo.android.ui.components.LocalToolbarBackdrop
import com.kudo.android.ui.components.screenMaterial
import com.kudo.android.ui.feed.FeedTabBar
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import com.kudo.android.ui.topic.ArticleBlock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

internal fun SavedImage.articleImage() = ArticleBlock.Image(thumbnailUrl, alt, ratio, originalUrl, filename)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ImageLibraryBrowser(library: ImageLibrary, folderId: String?, selectFolder: (String?) -> Unit,
    open: (SavedImage) -> Unit, modifier: Modifier = Modifier, selection: Set<String>? = null,
    source: ((ImageSource) -> Unit)? = null, remove: ((SavedImage) -> Unit)? = null,
    changeFolder: ((SavedImage) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null) {
    val folders = listOf(ImageFolder("all", "全部")) + library.folders
    val selectedId = folderId ?: "all"
    val pager = rememberPagerState(initialPage = folders.indexOfFirst { it.id == selectedId }.coerceAtLeast(0), pageCount = { folders.size })
    val scope = rememberCoroutineScope()
    val backdrop = if (selection == null) rememberLayerBackdrop() else null
    val behavior = TopAppBarDefaults.enterAlwaysScrollBehavior(canScroll = { header != null })
    var headerHeight by remember { mutableIntStateOf(0) }
    val top = with(LocalDensity.current) { headerHeight.toDp() }
    val currentFolder by rememberUpdatedState(selectedId)
    val currentSelect by rememberUpdatedState(selectFolder)
    LaunchedEffect(pager, folders.map { it.id }) {
        val target = folders.indexOfFirst { it.id == currentFolder }.coerceAtLeast(0)
        if (pager.currentPage != target) pager.scrollToPage(target)
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
            folders.getOrNull(index)?.let { currentSelect(it.id.takeUnless { id -> id == "all" }) }
        }
    }
    LaunchedEffect(selectedId) {
        val target = folders.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        if (pager.currentPage != target && !pager.isScrollInProgress) pager.scrollToPage(target)
    }
    Box(modifier.then(if (backdrop != null) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
        .nestedScroll(behavior.nestedScrollConnection)) {
        HorizontalPager(pager, Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier).testTag("library-pager"),
            key = { folders.getOrNull(it)?.id ?: "removed:$it" }) { index ->
            val id = (folders.getOrNull(index) ?: return@HorizontalPager).id.takeUnless { it == "all" }
            Box(Modifier.fillMaxSize().then(if (pager.settledPage == index) Modifier else Modifier.clearAndSetSemantics {})) {
                ImageLibraryGrid(library.images.filter { id == null || it.folderId == id }, open, selection, source, remove, changeFolder,
                    PaddingValues(start = 12.dp, end = 12.dp, top = top + 12.dp, bottom = 12.dp))
            }
        }
        CompositionLocalProvider(LocalToolbarBackdrop provides backdrop, LocalToolbarActions provides (backdrop != null)) {
            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
                .then(if (backdrop != null) Modifier.screenMaterial(backdrop) else Modifier)
                .then(if (header != null) Modifier.statusBarsPadding() else Modifier)) {
                if (header != null) Column(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    behavior.state.heightOffsetLimit = -placeable.height.toFloat()
                    val offset = behavior.state.heightOffset.roundToInt()
                    layout(placeable.width, (placeable.height + offset).coerceAtLeast(0)) { placeable.placeRelative(0, offset) }
                }.testTag("library-title-bar")) { header() }
                FeedTabBar(folders.map { it.id to it.name }, folders.getOrNull(pager.currentPage)?.id ?: "all",
                    containerColor = Color.Transparent, modifier = Modifier.testTag("library-folders")) { id ->
                    val target = folders.indexOfFirst { it.id == id }
                    if (target >= 0) scope.launch { pager.animateScrollToPage(target) }
                }
            }
        }
    }
}

@Composable
private fun ImageLibraryGrid(images: List<SavedImage>, open: (SavedImage) -> Unit, selection: Set<String>?,
    source: ((ImageSource) -> Unit)?, remove: ((SavedImage) -> Unit)?, changeFolder: ((SavedImage) -> Unit)?, padding: PaddingValues) {
        if (images.isEmpty()) Box(Modifier.fillMaxSize().scrollable(rememberScrollableState { 0f }, Orientation.Vertical)
            .padding(padding), contentAlignment = Alignment.Center) {
            Text("暂无收藏图片", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyVerticalGrid(GridCells.Adaptive(if (selection == null) 148.dp else 112.dp), Modifier.fillMaxSize().testTag("library-images"),
            contentPadding = padding, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images, key = { it.id }) { image ->
                val checked = selection?.contains(image.id) == true
                Column(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).testTag("library-item-${image.id}")) {
                    var failed by remember(image.thumbnailUrl) { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().aspectRatio(1.15f).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f))
                        .then(if (selection == null && changeFolder != null) Modifier.combinedClickable(role = Role.Button,
                            onLongClickLabel = "修改分类", onLongClick = { changeFolder(image) }, onClick = { open(image) })
                            else Modifier.clickable(role = if (selection != null) Role.Checkbox else Role.Button) { open(image) })
                        .semantics { if (selection != null) selected = checked }.testTag("library-image-${image.id}")) {
                        AsyncImage(image.thumbnailUrl, image.alt.ifBlank { "收藏图片" }, Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            onError = { failed = true }, onSuccess = { failed = false })
                        if (failed) Text("加载失败，点击查看原图", Modifier.align(Alignment.Center).padding(10.dp), style = MaterialTheme.typography.bodySmall)
                        if (selection != null) Icon(if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            if (checked) "已选择" else "未选择", Modifier.align(Alignment.TopEnd).padding(8.dp)
                                .clip(RoundedCornerShape(50)).background(if (checked) LogoYellow else MaterialTheme.colorScheme.surface),
                            tint = if (checked) Ink else MaterialTheme.colorScheme.onSurface)
                    }
                    if (selection == null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (image.source != null && source != null) Text("原帖 · ${image.source.postNumber} 楼",
                                Modifier.weight(1f).clickable(role = Role.Button) { source(image.source) }.heightIn(min = 48.dp)
                                    .padding(horizontal = 10.dp, vertical = 14.dp).semantics {
                                        contentDescription = "打开${image.source.title.ifBlank { "来源帖子" }}，第 ${image.source.postNumber} 楼"
                                    }.testTag("library-source-${image.id}"),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Spacer(Modifier.weight(1f))
                            if (remove != null) CommunityIconButton(Icons.Rounded.DeleteOutline, "移除收藏图片", { remove(image) }, Modifier.testTag("library-remove-${image.id}"))
                        }
                    }
                }
            }
        }
}
