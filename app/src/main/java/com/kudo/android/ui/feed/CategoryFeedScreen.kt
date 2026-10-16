package com.kudo.android.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.kudo.android.data.*
import com.kudo.android.ui.components.CategoryIcons
import com.kudo.android.ui.components.screenMaterial
import com.kudo.android.ui.components.TopBarIconButton
import com.kudo.android.ui.components.liquid.LiquidButton
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun CategoryFeedScreen(category: Category, state: FeedState, user: Member?, select: (String) -> Unit,
    refresh: () -> Unit, more: () -> Unit, openTopic: (Int) -> Unit, openUser: (String) -> Unit,
    search: () -> Unit, login: () -> Unit, back: () -> Unit, modifier: Modifier, listStyle: TopicListStyle,
    pages: Map<String, FeedState>, createTopic: (Int) -> Unit, glass: GlassMode) {
    val backdrop = rememberLayerBackdrop()
    val tint = remember(category.color) {
        runCatching { Color(android.graphics.Color.parseColor("#${category.color.removePrefix("#")}")) }.getOrDefault(LogoYellow)
    }
    val background = tint.copy(alpha = .14f).compositeOver(MaterialTheme.colorScheme.surface)
    var maximum by remember(category.id) { mutableFloatStateOf(0f) }
    var collapse by rememberSaveable(category.id, user?.id, user?.unifiedNew) { mutableFloatStateOf(0f) }
    val currentMaximum by rememberUpdatedState(maximum)
    val nested = remember(category.id, user?.id, user?.unifiedNew) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f) return Offset.Zero
            val previous = collapse
            collapse = (collapse - available.y).coerceIn(0f, currentMaximum)
            return Offset(0f, previous - collapse)
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (available.y <= 0f) return Offset.Zero
            val previous = collapse
            collapse = (collapse - available.y).coerceIn(0f, currentMaximum)
            return Offset(0f, previous - collapse)
        }
    } }
    var headerHeight by remember { mutableIntStateOf(0) }
    val contentTopPadding = with(LocalDensity.current) { headerHeight.toDp() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val tabs = FeedFilter.available(user != null).map { it.value to it.label }
        key(category.id, user?.id, user?.unifiedNew) {
            val pager = rememberPagerState(initialPage = tabs.indexOfFirst { it.first == state.filter }.coerceAtLeast(0), pageCount = { tabs.size })
            val scope = rememberCoroutineScope()
            val currentSelect by rememberUpdatedState(select)
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
                    tabs.getOrNull(index)?.first?.let(currentSelect)
                }
            }
            LaunchedEffect(state.filter) {
                val index = tabs.indexOfFirst { it.first == state.filter }
                if (index >= 0 && pager.currentPage != index && !pager.isScrollInProgress) pager.scrollToPage(index)
            }
            HorizontalPager(pager, Modifier.fillMaxSize().layerBackdrop(backdrop).testTag("category-pager"), key = { tabs[it].first }) { index ->
                val value = tabs[index].first
                val page = if (value == state.filter) state else pages[value]?.takeIf { it.accountId == user?.id }
                    ?: FeedState(filter = value, accountId = user?.id, categories = state.categories)
                FeedPage(page, pager.settledPage == index && value == state.filter, { collapse = 0f },
                    refresh, more, openTopic, openUser, login, listStyle, nested,
                    expandHeader = {
                        androidx.compose.animation.core.animate(collapse, 0f) { value, _ -> collapse = value }
                    }, contentTopPadding = contentTopPadding)
            }
            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }
                .screenMaterial(backdrop, glass, fraction = { if (maximum > 0f) collapse / maximum else 0f }, expandedColor = background).testTag("category-header")
                .scrollable(rememberScrollableState { delta ->
                    val previous = collapse
                    collapse = (collapse - delta).coerceIn(0f, maximum)
                    previous - collapse
                }, Orientation.Vertical)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("category-toolbar"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back, backdrop = backdrop)
                    if (maximum > 0 && collapse >= maximum - 1f) {
                        Icon(CategoryIcons.get(category.icon), null, Modifier.size(24.dp).testTag("category-toolbar-icon"), tint = tint)
                        Text(category.name, Modifier.weight(1f).padding(horizontal = 9.dp).testTag("category-toolbar-name"),
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else Spacer(Modifier.weight(1f))
                    TopBarIconButton(Icons.Rounded.Search, "搜索", search, backdrop = backdrop)
                }
                Column(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    maximum = placeable.height.toFloat()
                    collapse = collapse.coerceIn(0f, maximum)
                    val offset = collapse.roundToInt()
                    layout(placeable.width, placeable.height - offset) { placeable.placeRelative(0, -offset) }
                }.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(64.dp).background(tint.copy(alpha = .14f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                            Icon(CategoryIcons.get(category.icon), null, Modifier.size(34.dp).testTag("category-expanded-icon"), tint = tint)
                        }
                        Text(category.name, Modifier.weight(1f), fontSize = 23.sp, lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (category.description.isNotBlank()) Text(category.description, Modifier.padding(top = 12.dp),
                        fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }

                FeedTabBar(tabs, tabs.getOrNull(pager.currentPage)?.first ?: state.filter, containerColor = Color.Transparent) { value ->
                    val index = tabs.indexOfFirst { it.first == value }
                    if (index >= 0) scope.launch { pager.animateScrollToPage(index) }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)) {
            LiquidButton(onClick = { if (user == null) login() else createTopic(category.id) }, backdrop = backdrop,
                mode = glass, tint = LogoYellow, contentColor = Ink, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(56.dp).testTag("category-publish")) {
                Icon(Icons.Rounded.Add, "在${category.name}发帖", Modifier.size(28.dp).testTag("category-publish-icon"))
            }
        }
    }
}
