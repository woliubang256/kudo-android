package com.kudo.android.ui.account

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.kudo.android.ui.topic.RichContent
import kotlinx.coroutines.*

@Composable
internal fun BadgeScreen(user: Member, repository: CommunityRepository, back: () -> Unit, login: () -> Unit, website: (String) -> Unit) {
    var badges by remember(user.id, user.username) { mutableStateOf(emptyList<UserBadge>()) }
    var loading by remember(user.id, user.username) { mutableStateOf(true) }
    var error by remember(user.id, user.username) { mutableStateOf<Throwable?>(null) }
    var refresh by remember(user.id, user.username) { mutableIntStateOf(0) }
    LaunchedEffect(user.id, user.username, refresh) {
        loading = true; error = null
        try { badges = withContext(Dispatchers.IO) { repository.userBadges(user.username) } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        finally { loading = false }
    }
    key(user.id, user.username) { BadgePage(badges, loading, error, { refresh++ }, back, login, website) }
}

@Composable
internal fun BadgePage(badges: List<UserBadge>, loading: Boolean = false, error: Throwable? = null,
    refresh: () -> Unit = {}, back: () -> Unit = {}, login: () -> Unit = {}, website: (String) -> Unit = {}) {
    var selected by remember { mutableStateOf<UserBadge?>(null) }
    val sheet = remember { MutableTransitionState(false) }
    val showing = sheet.currentState || sheet.targetState
    val scroll = rememberLazyGridState()
    val columns = GridCells.Adaptive(100.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.15f))
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MaterialPageScaffold(modifier = Modifier.then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (showing) cancelFocusChange() } }.focusGroup(), header = {
            AccountPageTopBar("我的徽章", back) {
                if (!loading && error == null) Text("已获得 ${badges.count { it.earned }} / ${badges.size}",
                    Modifier.padding(horizontal = 8.dp).testTag("badge-statistics"), maxLines = 1,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }) { padding ->
            LazyVerticalGrid(columns, Modifier.fillMaxSize().testTag("badge-grid").quickScrollTarget(scroll, enabled = !showing), state = scroll, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) item(span = { GridItemSpan(maxLineSpan) }) { LoadingRow() }
                else if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(error, refresh, login) }
                else {
                    items(badges, key = { it.id }) { badge ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { selected = badge; sheet.targetState = true }
                            .semantics { stateDescription = if (badge.earned) "已获得" else "未获得" }
                            .heightIn(min = 112.dp).padding(horizontal = 6.dp, vertical = 12.dp).testTag("badge-${badge.id}"),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            BadgeEmblem(badge, Modifier.size(76.dp))
                            Text(badge.name, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (badge.earned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (badges.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState("暂无徽章", "", Icons.Rounded.WorkspacePremium)
                    }
                }
            }
        }
        CommunitySheet(sheet, selected?.name ?: "徽章详情", { sheet.targetState = false }, showActions = false) {
            selected?.let { badge ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BadgeEmblem(badge, Modifier.size(64.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(badge.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (badge.earned) "已获得" else "未获得", style = MaterialTheme.typography.titleMedium)
                        Text(when (badge.type) { 1 -> "金色徽章"; 2 -> "银色徽章"; else -> "铜色徽章" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (badge.earned && badge.grantedAt.isNotBlank()) Text("获得时间：${badge.grantedAt.take(10)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (badge.earned) Text("已获得 ${badge.count} 次", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (badge.description.isNotBlank()) Text(plainText(badge.description), style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("获得条件", style = MaterialTheme.typography.titleSmall)
                if (badge.longDescription.isNotBlank() || badge.description.isNotBlank()) {
                    RichContent(badge.longDescription.ifBlank { badge.description }, website, website, galleryKey = "badge-${badge.id}")
                } else Text("网站暂未提供获得条件说明", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                CommunityButton("在社区查看", { sheet.targetState = false; website(badge.url) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun badgeTint(badge: UserBadge): Color = if (!badge.earned) MaterialTheme.colorScheme.outline else when (badge.type) {
        1 -> Color(0xFFE5BF00); 2 -> Color(0xFFA8A8A8); else -> Color(0xFFCD7F32)
    }

@Composable
private fun BadgeEmblem(badge: UserBadge, modifier: Modifier = Modifier) {
    Box(modifier.testTag("badge-frame-${badge.id}").clip(CircleShape)
        .background(MaterialTheme.colorScheme.surface).border(2.dp, badgeTint(badge), CircleShape),
        contentAlignment = Alignment.Center) {
        BadgeLogo(badge, Modifier.fillMaxSize(.5f))
    }
}

@Composable
private fun BadgeLogo(badge: UserBadge, modifier: Modifier = Modifier) {
    val tint = badgeTint(badge)
    val context = LocalContext.current
    val vector = remember(badge.icon, context) { BadgeIcons.get(context, badge.icon) }
    var failed by remember(badge.imageUrl) { mutableStateOf(false) }
    val image = badge.imageUrl?.let(::mediaUrl)
    if (image != null && !failed) AsyncImage(image, "${badge.name}徽章图标", modifier.testTag("badge-logo-${badge.id}"),
        contentScale = ContentScale.Fit,
        colorFilter = if (badge.earned) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
        onError = { failed = true })
    else Icon(vector, "${badge.name}徽章图标", modifier.testTag("badge-logo-${badge.id}"), tint = tint)
}
