package com.kudo.android.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import com.kudo.android.ui.components.screenMaterial
import com.kudo.android.ui.components.TopBarIconButton
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.*
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A tag starts at the same compact, already-collapsed state as a category toolbar. */
@Composable
internal fun TagFeedScreen(slug: String, state: FeedState, user: Member?, select: (String) -> Unit,
    refresh: () -> Unit, more: () -> Unit, openTopic: (Int) -> Unit, openUser: (String) -> Unit,
    search: () -> Unit, login: () -> Unit, back: () -> Unit, listStyle: TopicListStyle,
    pages: Map<String, FeedState>, modifier: Modifier = Modifier, name: String = "") {
    val title = state.tag?.name ?: name.ifBlank { if (slug.matches(Regex("[0-9]+-tag"))) "标签" else slug }
    val backdrop = rememberLayerBackdrop()
    var headerHeight by remember { mutableIntStateOf(0) }
    val contentTopPadding = with(LocalDensity.current) { headerHeight.toDp() }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val tabs = FeedFilter.available(user != null).map { it.value to it.label }
        key(slug, user?.id, user?.unifiedNew) {
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
            HorizontalPager(pager, Modifier.fillMaxSize().layerBackdrop(backdrop).testTag("tag-pager"), key = { tabs[it].first }) { index ->
                val value = tabs[index].first
                val page = if (value == state.filter) state else pages[value]?.takeIf { it.accountId == user?.id }
                    ?: FeedState(filter = value, accountId = user?.id, categories = state.categories)
                FeedPage(page, pager.settledPage == index && value == state.filter, {},
                    refresh, more, openTopic, openUser, login, listStyle, contentTopPadding = contentTopPadding)
            }
            Column(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }.screenMaterial(backdrop)
                .statusBarsPadding().testTag("tag-header")) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("tag-toolbar"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back, backdrop = backdrop)
                    Icon(Icons.Rounded.Tag, "标签", Modifier.size(21.dp), tint = LogoYellow)
                    Text(title, Modifier.weight(1f).padding(horizontal = 8.dp).testTag("tag-toolbar-name"),
                        fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TopBarIconButton(Icons.Rounded.Search, "搜索", search, backdrop = backdrop)
                }
                FeedTabBar(tabs, tabs.getOrNull(pager.currentPage)?.first ?: state.filter, containerColor = Color.Transparent) { value ->
                    val index = tabs.indexOfFirst { it.first == value }
                    if (index >= 0) scope.launch { pager.animateScrollToPage(index) }
                }
            }
        }
    }
}
