package com.kudo.android.ui.account

import android.os.Build
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kudo.android.BuildConfig
import com.kudo.android.data.*
import com.kudo.android.ui.components.Avatar
import com.kudo.android.ui.components.AvatarPresentation
import com.kudo.android.ui.components.quickScrollTarget
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

@Composable
internal fun MemberProfileScreen(user: Member, repository: CommunityRepository?, settings: () -> Unit,
    about: () -> Unit, profile: () -> Unit, destination: (AccountDestination) -> Unit, downloads: () -> Unit = {},
    statusBarStyle: (Boolean?) -> Unit) {
    var member by remember(user.id, user.username) { mutableStateOf(user) }
    var summary by remember(user.id, user.username) { mutableStateOf<UserSummary?>(null) }
    var summaryError by remember(user.id, user.username) { mutableStateOf(false) }
    var refresh by remember(user.id) { mutableIntStateOf(0) }
    var loading by remember(user.id) { mutableStateOf(repository != null) }
    val scroll = rememberLazyListState()
    val backdrop = rememberLayerBackdrop()
    val glass = LocalDisplaySettings.current.glass
    val canBlur = glass != GlassMode.TRANSLUCENT && Build.VERSION.SDK_INT >= 31
    val canRefract = glass == GlassMode.LIQUID && Build.VERSION.SDK_INT >= 33
    val surface = MaterialTheme.colorScheme.surface
    val themeInk = MaterialTheme.colorScheme.onSurface
    var backgroundLoaded by remember(user.id, member.profileBackground) { mutableStateOf(false) }
    LaunchedEffect(user.id, user.username, repository, refresh) {
        if (repository == null) return@LaunchedEffect
        loading = true; summaryError = false
        supervisorScope {
            launch {
                try {
                    withContext(Dispatchers.IO) { repository.user(user.username) }
                        .takeIf { it.id == user.id && it.username.equals(user.username, true) }?.let { member = it }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            launch {
                try { summary = withContext(Dispatchers.IO) { repository.userSummary(user.username) } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { summaryError = true }
                finally { loading = false }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { statusBarStyle(null) } }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val density = LocalDensity.current
        val minimum = WindowInsets.statusBars.getTop(density) + with(density) { 60.dp.toPx() }
        val minimumHeight = with(density) { minimum.toDp() }
        val heightLimit = (maxHeight - 120.dp).coerceAtLeast(minimumHeight)
        // Preserve the existing avatar and inner spacing independently of the natural header height.
        val layoutRoom = maxWidth.coerceAtMost(heightLimit)
        var measuredHeight by remember(user.id) { mutableIntStateOf(0) }
        val height = with(density) {
            (if (measuredHeight > 0) measuredHeight.toDp() else layoutRoom).coerceIn(minimumHeight, heightLimit).toPx()
        }
        val maximum = (height - minimum).coerceAtLeast(0f)
        var collapse by remember(user.id) { mutableFloatStateOf(0f) }
        val latestMaximum by rememberUpdatedState(maximum)
        val visibleCollapse = collapse.coerceIn(0f, maximum)
        val fraction = if (maximum > 0f) visibleCollapse / maximum else 1f
        val compactIdentityAlpha = profileToolbarIdentityAlpha(fraction)
        val collapsed = fraction >= .999f
        val viewportHeight = with(density) { (height - visibleCollapse).toDp() }
        val requestedStatusStyle = if (compactIdentityAlpha >= .5f) surface.luminance() > .5f else !backgroundLoaded
        SideEffect { statusBarStyle(requestedStatusStyle) }
        LaunchedEffect(maximum) { collapse = collapse.coerceIn(0f, maximum) }
        val nested = remember(user.id) { object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val previous = collapse
                collapse = (collapse - available.y).coerceIn(0f, latestMaximum)
                return Offset(0f, previous - collapse)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                val previous = collapse
                collapse = (collapse - available.y).coerceIn(0f, latestMaximum)
                return Offset(0f, previous - collapse)
            }
        } }
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(MaterialTheme.colorScheme.background)
            .nestedScroll(nested).testTag("member-content-backdrop")) {
            Box(Modifier.fillMaxWidth().height(viewportHeight).clipToBounds().zIndex(1f)
                .testTag("member-header-viewport")
                .scrollable(rememberScrollableState { delta ->
                    val previous = collapse
                    collapse = (collapse - delta).coerceIn(0f, maximum)
                    previous - collapse
                }, Orientation.Vertical)) {
                MemberHeader(member, summary, loading, summaryError, { refresh++ }, profile,
                    { destination(AccountDestination.TRUST) }, fraction,
                    avatarSize = if (layoutRoom < 280.dp) 48.dp else 64.dp,
                    identityGap = if (layoutRoom < 320.dp) 12.dp else 40.dp,
                    modifier = Modifier.graphicsLayer { alpha = 1f - compactIdentityAlpha }
                        .offset { IntOffset(0, -visibleCollapse.toInt()) }
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .heightIn(min = minimumHeight, max = heightLimit)
                        .onSizeChanged {
                            if (collapsed && collapse > 0f) collapse = (it.height - minimum).coerceAtLeast(0f)
                            measuredHeight = it.height
                        }) {
                    backgroundLoaded = it
                }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("member-profile")
                .quickScrollTarget(scroll, expandHeader = {
                    androidx.compose.animation.core.animate(collapse, 0f) { value, _ -> collapse = value }
                }), state = scroll,
                contentPadding = PaddingValues(top = viewportHeight + 8.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "shortcuts") {
                    BoxWithConstraints(Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp, vertical = 12.dp)) {
                        val columns = if (maxWidth < 300.dp) 2 else 4
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(AccountDestination.TOPICS, AccountDestination.BOOKMARKS, AccountDestination.LIKES, AccountDestination.HISTORY)
                                .chunked(columns).forEach { entries ->
                                    Row(Modifier.fillMaxWidth()) { entries.forEach { entry ->
                                        val (icon, color) = destinationIcon(entry)
                                        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { destination(entry) }
                                            .padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Icon(icon, null, Modifier.size(25.dp), tint = color)
                                            Text(entry.title, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                                        }
                                    } }
                                }
                        }
                    }
                }
                item(key = "community") {
                    Column(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)) {
                        val entries = listOf(AccountDestination.MESSAGES, AccountDestination.CHAT, AccountDestination.PENDING,
                            AccountDestination.BADGES, AccountDestination.TRUST) +
                            (if (member.trustLevel >= 3) listOf(AccountDestination.INVITES) else emptyList())
                        entries.forEach { entry ->
                            val (icon, color) = destinationIcon(entry)
                            CompactAccountRow(icon, color, entry.title, { destination(entry) })
                        }
                    }
                }
                item(key = "settings") {
                    Column(Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)) {
                        CompactAccountRow(Icons.Rounded.Palette, Color(0xFF9B6FE6), "设置", settings)
                        CompactAccountRow(Icons.Rounded.Download, Color(0xFF00A896), "下载管理", downloads)
                        CompactAccountRow(Icons.Rounded.Info, Color(0xFF328AE5), "关于酷DO", about, BuildConfig.VERSION_NAME)
                    }
                }
                item { Text("真诚 · 友善 · 团结 · 专业", Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center,
                    fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (compactIdentityAlpha > 0f) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .graphicsLayer { alpha = compactIdentityAlpha }
            .drawPlainBackdrop(backdrop, shape = { RoundedCornerShape(0.dp) }, onDrawSurface = {
                drawRect(surface.copy(alpha = if (canBlur) .2f else .94f))
            }, effects = {
                if (canBlur) { vibrancy(); blur(4.dp.toPx()) }
                if (canRefract) lens(24.dp.toPx(), 24.dp.toPx())
            }).testTag("member-toolbar-material")) {
            MemberCompactHeader(member, lerp(if (backgroundLoaded) Color.White else Ink, themeInk, compactIdentityAlpha), profile,
                { destination(AccountDestination.TRUST) }, Modifier, backdrop)
        }
    }
}

@Composable
internal fun CompactAccountRow(icon: ImageVector, color: Color, title: String, click: () -> Unit, value: String? = null) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = click).heightIn(min = 50.dp)
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(21.dp), tint = color)
        Text(title, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 21.sp)
        if (value != null) Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

private fun destinationIcon(entry: AccountDestination): Pair<ImageVector, Color> = when (entry) {
    AccountDestination.TOPICS -> Icons.Rounded.Article to Color(0xFF2196F3)
    AccountDestination.BOOKMARKS -> Icons.Rounded.BookmarkBorder to Color(0xFFFF9800)
    AccountDestination.DRAFTS -> Icons.Rounded.EditNote to Color(0xFF26A69A)
    AccountDestination.LIKES -> Icons.Rounded.ThumbUp to Color(0xFF26A69A)
    AccountDestination.HISTORY -> Icons.Rounded.History to Color(0xFFA374EF)
    AccountDestination.MESSAGES -> Icons.Rounded.MailOutline to Color(0xFF5B83E8)
    AccountDestination.CHAT -> Icons.Rounded.Forum to Color(0xFF00A68E)
    AccountDestination.PENDING -> Icons.Rounded.PendingActions to Color(0xFFF2A000)
    AccountDestination.BADGES -> Icons.Rounded.WorkspacePremium to Color(0xFFFF9819)
    AccountDestination.TRUST -> Icons.Rounded.VerifiedUser to Color(0xFF43A047)
    AccountDestination.INVITES -> Icons.Rounded.Link to Color(0xFF139FCE)
}
