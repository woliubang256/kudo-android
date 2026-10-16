package com.kudo.android.ui.account

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import com.kyant.backdrop.Backdrop

internal fun profileHeaderImage(member: Member, card: String): String = card.ifBlank {
    member.animatedAvatar.ifBlank { member.avatar.replace("{size}", "512") }
}

@Composable
internal fun PublicProfileHeader(profile: PublicProfile, summary: ProfileSummary?, card: String, viewerId: Int?,
    busy: Boolean, follow: () -> Unit, message: () -> Unit, follows: (Boolean) -> Unit,
    modifier: Modifier = Modifier, openLink: (String) -> Unit = {}, collapseFraction: Float = 0f, details: () -> Unit = {}) {
    val member = profile.member
    val avatar = member.animatedAvatar.ifBlank { member.avatar.replace("{size}", "512") }.let(::mediaUrl)
    val source = profileHeaderImage(member, card).let(::mediaUrl)
    BoxWithConstraints(modifier.fillMaxWidth().background(Ink).testTag("public-profile-header")) {
        val pixels = with(LocalDensity.current) { maxWidth.roundToPx().coerceIn(128, 1200) }
        val compact = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.15f || maxHeight < 330.dp
        val minimal = maxHeight < 250.dp
        AvatarImage(source, pixels, true, Modifier.matchParentSize().testTag("public-profile-background"), fallbackUrl = avatar, placeholder = {})
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = .24f + .08f * collapseFraction), Color.Black.copy(alpha = .40f + .08f * collapseFraction)))))
        if (collapseFraction < 1f) Column(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - collapseFraction * 2f).coerceIn(0f, 1f) }
            .statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 52.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                Avatar(member, if (compact) 40.dp else 60.dp)
                Spacer(Modifier.weight(1f))
                if (member.id != viewerId) {
                    ProfileFollowButton(member, viewerId, busy, follow, Modifier.testTag("profile-follow"))
                    if (viewerId == null || profile.canMessage) Box(Modifier.size(40.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = .2f)).clickable(role = Role.Button, onClick = message), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.MailOutline, "发送私信", Modifier.size(22.dp), tint = Color.White)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(member.displayName, Modifier.weight(1f, fill = false), color = Color.White,
                        fontSize = if (compact) 20.sp else 25.sp, lineHeight = if (compact) 24.sp else 30.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Lv.${member.trustLevel}", Modifier.clip(RoundedCornerShape(50)).background(LogoYellow).padding(horizontal = 8.dp, vertical = 3.dp),
                        color = Ink, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.clickable(role = Role.Button, onClick = details).semantics { contentDescription = "查看完整个人资料" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("@${member.username}" + profile.title.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                        Modifier.weight(1f, fill = false), color = Color.White.copy(alpha = .8f), fontSize = 11.sp, lineHeight = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(14.dp), tint = Color.White.copy(alpha = .8f))
                }
            }
            if (!compact && member.bio.isNotBlank()) Text(member.bio, Modifier.fillMaxWidth().clickable(onClick = details),
                color = Color.White.copy(alpha = .92f), fontSize = 13.sp, lineHeight = 20.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!minimal) FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaderStat(profile.following, "关注", Modifier.clickable(role = Role.Button) { follows(false) }.testTag("profile-following"))
                HeaderStat(profile.followers, "粉丝", Modifier.clickable(role = Role.Button) { follows(true) }.testTag("profile-followers"))
            }
            if (!minimal) FlowRow(horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("likes_received" to "获赞", "days_visited" to "访问", "topic_count" to "话题", "post_count" to "回复").forEach { (key, label) ->
                    HeaderStat(summary?.counts?.get(key), label)
                }
            }
            if (!compact) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (profile.lastSeen.isNotBlank()) Text("${relativeTime(profile.lastSeen)}活跃", Modifier.clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.White.copy(alpha = .85f), fontSize = 10.sp, lineHeight = 15.sp)
                if (profile.location.isNotBlank()) Text(profile.location, color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                if (profile.website.isNotBlank()) Text(profile.website, Modifier.clickable { openLink(profile.website) }, color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HeaderStat(value: Int?, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(value?.let(::compactNumber) ?: "—", color = Color.White, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Color.White.copy(alpha = .78f), fontSize = 11.sp, lineHeight = 18.sp)
    }
}

@Composable
internal fun ProfileToolbar(member: Member, collapsed: Boolean, viewerId: Int?, busy: Boolean, follow: () -> Unit,
    back: () -> Unit, search: () -> Unit, more: () -> Unit, modifier: Modifier = Modifier,
    collapseFraction: Float = if (collapsed) 1f else 0f, ink: Color = Color.White,
    backdrop: Backdrop? = LocalToolbarBackdrop.current) {
    val color = ink
    val identityAlpha = profileToolbarIdentityAlpha(collapseFraction)
    Row(modifier.fillMaxWidth().testTag("profile-toolbar")
        .statusBarsPadding().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back, backdrop = backdrop, tint = color)
        Box(Modifier.graphicsLayer { alpha = identityAlpha }) {
            Avatar(member, 30.dp, Modifier.testTag("profile-toolbar-avatar"))
        }
        Text(member.displayName, Modifier.weight(1f).padding(horizontal = 6.dp).graphicsLayer { alpha = identityAlpha }, fontSize = 15.sp,
            fontWeight = FontWeight.Medium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.graphicsLayer { alpha = identityAlpha }) {
            ProfileFollowButton(member, viewerId, busy, follow, Modifier.testTag("profile-toolbar-follow"), compact = true)
        }
        TopBarIconButton(Icons.Rounded.Search, "搜索该用户的帖子", search, backdrop = backdrop, tint = color)
        TopBarIconButton(Icons.Rounded.MoreVert, "个人主页更多操作", more, backdrop = backdrop, tint = color)
    }
}

internal fun profileToolbarIdentityAlpha(collapseFraction: Float): Float =
    ((collapseFraction - .5f) * 2f).coerceIn(0f, 1f)

@Composable
private fun ProfileFollowButton(member: Member, viewerId: Int?, busy: Boolean, follow: () -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false) {
    if (member.id == viewerId || (viewerId != null && !member.canFollow && !member.isFollowed)) return
    Box(modifier.clip(RoundedCornerShape(50)).background(LogoYellow.copy(alpha = if (busy) .6f else 1f))
        .clickable(enabled = !busy, role = Role.Button, onClick = follow)
        .padding(horizontal = if (compact) 12.dp else 24.dp, vertical = if (compact) 6.dp else 9.dp), contentAlignment = Alignment.Center) {
        Text(if (busy) "处理中…" else if (member.isFollowed) "已关注" else "关注", color = Ink,
            fontSize = if (compact) 12.sp else 13.sp, lineHeight = 18.sp, maxLines = 1)
    }
}

@Composable
internal fun PublicProfileDetails(profile: PublicProfile, summary: ProfileSummary?, openLink: (String) -> Unit) {
    Text(profile.member.displayName, style = MaterialTheme.typography.titleMedium)
    Text("@${profile.member.username} · Lv.${profile.member.trustLevel}")
    if (profile.title.isNotBlank()) Text(profile.title)
    if (profile.member.bio.isNotBlank()) Text(profile.member.bio)
    Text("${profile.following?.let(::compactNumber) ?: "—"} 关注 · ${profile.followers?.let(::compactNumber) ?: "—"} 粉丝")
    Text(listOf("likes_received" to "获赞", "days_visited" to "访问", "topic_count" to "话题", "post_count" to "回复")
        .joinToString(" · ") { (key, label) -> "${summary?.counts?.get(key)?.let(::compactNumber) ?: "—"} $label" })
    if (profile.lastSeen.isNotBlank()) Text("${relativeTime(profile.lastSeen)}活跃")
    if (profile.location.isNotBlank()) Text(profile.location)
    if (profile.website.isNotBlank()) Text(profile.website, Modifier.clickable { openLink(profile.website) })
}
