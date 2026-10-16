package com.kudo.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.Ink
import com.kyant.backdrop.Backdrop

@Composable
internal fun MemberHeader(member: Member, summary: UserSummary?, loading: Boolean, error: Boolean,
    retry: () -> Unit, profile: () -> Unit, trust: () -> Unit, fraction: Float,
    avatarSize: Dp, identityGap: Dp, modifier: Modifier,
    onLoaded: (Boolean) -> Unit) {
    val avatar = member.animatedAvatar.ifBlank { member.avatar.replace("{size}", "512") }.let(::mediaUrl)
    var loaded by remember(member.id, member.profileBackground, avatar) { mutableStateOf(false) }
    SideEffect { onLoaded(loaded) }
    val ink = if (loaded) Color.White else Ink
    BoxWithConstraints(modifier.fillMaxWidth().background(Color.White).testTag("member-header")) {
        val pixels = with(LocalDensity.current) { maxWidth.roundToPx().coerceIn(128, 1200) }
        AvatarImage(member.profileBackground.takeIf(String::isNotBlank)?.let(::mediaUrl) ?: avatar, pixels, true,
            Modifier.matchParentSize().testTag("member-background"), fallbackUrl = avatar,
            onLoaded = { loaded = it }, placeholder = {})
        if (loaded) Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = .24f + .08f * fraction), Color.Black.copy(alpha = .40f + .08f * fraction)))))
        // Hidden content must still measure at its expanded size while the viewport is collapsed.
        Column(Modifier.fillMaxWidth().graphicsLayer { alpha = (1f - 2f * fraction).coerceIn(0f, 1f) }
            .then(if (fraction >= .5f) Modifier.clearAndSetSemantics {} else Modifier)
            .statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = profile).testTag("member-identity"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(member, avatarSize,
                    Modifier.testTag("member-avatar"), presentation = AvatarPresentation.ACCOUNT, onClick = profile)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MemberName(member, ink, trust)
                    Text("@${member.username}", color = ink.copy(alpha = .78f), fontSize = 12.sp, lineHeight = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ProfileArrow(ink, profile)
            }
            Row(Modifier.fillMaxWidth().padding(top = identityGap),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("访问天数" to summary?.daysVisited, "已读帖子" to summary?.postsRead,
                    "获赞" to summary?.likesReceived, "发帖数" to summary?.posts).forEach { (label, value) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(value?.let(::compactNumber) ?: "—", color = ink, fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                        Text(label, color = ink.copy(alpha = .78f), fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
            if (loading || error) Text(if (loading) "正在读取统计…" else "统计暂不可用 · 点击重试",
                Modifier.align(Alignment.CenterHorizontally).then(if (error) Modifier.clickable(onClick = retry) else Modifier),
                color = ink.copy(alpha = .8f), fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
internal fun MemberCompactHeader(member: Member, ink: Color, profile: () -> Unit, trust: () -> Unit, modifier: Modifier,
    backdrop: Backdrop) {
    Row(modifier.fillMaxWidth().statusBarsPadding().height(60.dp).clickable(role = Role.Button, onClick = profile)
        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).testTag("member-compact-header"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(member, 30.dp, presentation = AvatarPresentation.ACCOUNT, onClick = profile)
        Box(Modifier.weight(1f)) { MemberName(member, ink, trust, compact = true) }
        TopBarIconButton(Icons.Rounded.ChevronRight, "打开个人主页", profile,
            Modifier.testTag("member-profile-arrow"), backdrop = backdrop, tint = ink)
    }
}

@Composable
private fun MemberName(member: Member, ink: Color, trust: () -> Unit, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(member.displayName, Modifier.weight(1f, fill = false), color = ink, fontSize = if (compact) 16.sp else 21.sp,
            lineHeight = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Lv.${member.trustLevel}", Modifier.clip(RoundedCornerShape(5.dp)).background(ink.copy(alpha = .12f))
            .clickable(role = Role.Button, onClick = trust).padding(horizontal = 7.dp, vertical = 3.dp),
            color = ink, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProfileArrow(ink: Color, profile: () -> Unit) {
    IconButton(profile, Modifier.size(40.dp).testTag("member-profile-arrow")) {
        Icon(Icons.Rounded.ChevronRight, "打开个人主页", Modifier.size(24.dp), tint = ink)
    }
}
