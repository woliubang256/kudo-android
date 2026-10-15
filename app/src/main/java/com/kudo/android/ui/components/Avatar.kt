package com.kudo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kudo.android.data.*
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.time.Instant

enum class AvatarPresentation { ACCOUNT, PUBLIC, PLAIN }

internal sealed interface AvatarDecoration {
    data class Flair(val value: AvatarFlair) : AvatarDecoration
    data class Status(val value: UserStatus) : AvatarDecoration
}

internal fun avatarDecoration(member: Member?, presentation: AvatarPresentation, now: Instant): AvatarDecoration? {
    member ?: return null
    if (presentation == AvatarPresentation.PLAIN) return null
    if (presentation == AvatarPresentation.PUBLIC) member.flair?.let { return AvatarDecoration.Flair(it) }
    return member.status?.takeIf { it.isActive(now) }?.let(AvatarDecoration::Status)
}

internal fun memberNameDecorations(member: Member, now: Instant): List<AvatarDecoration> {
    val onAvatar = avatarDecoration(member, AvatarPresentation.PUBLIC, now)
    return listOfNotNull(
        member.flair?.let(AvatarDecoration::Flair),
        member.status?.takeIf { it.isActive(now) }?.let(AvatarDecoration::Status),
    ).filterNot { it == onAvatar }
}

internal val LocalAvatarMetadata = staticCompositionLocalOf<AvatarMetadataCache?> { null }

@Composable
internal fun AvatarEnvironment(repository: CommunityRepository, accountId: Int?, content: @Composable () -> Unit) {
    val parentScope = rememberCoroutineScope()
    val cache = remember(repository, accountId) {
        AvatarMetadataCache(CoroutineScope(parentScope.coroutineContext + Dispatchers.IO), repository::userCards, repository::emojiUrls,
            loadEmojiGroups = repository::emojiGroups)
    }
    DisposableEffect(cache) { onDispose { cache.close() } }
    CompositionLocalProvider(LocalAvatarMetadata provides cache, content = content)
}

@Composable
fun Avatar(
    member: Member?,
    size: Dp,
    modifier: Modifier = Modifier,
    presentation: AvatarPresentation = AvatarPresentation.PUBLIC,
    onClick: (() -> Unit)? = null,
) {
    val metadata = LocalAvatarMetadata.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var visible by remember { mutableStateOf(false) }
    val active = visible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var enriched by remember(member, metadata) { mutableStateOf<Member?>(null) }
    LaunchedEffect(member, metadata, active, presentation) {
        if (active && member != null && metadata != null && presentation == AvatarPresentation.PUBLIC) enriched = metadata.resolve(member)
    }
    val shown = enriched ?: member
    var now by remember(shown?.status) { mutableStateOf(Instant.now()) }
    LaunchedEffect(shown?.status, active) {
        now = Instant.now()
        val expiry = shown?.status?.endsAt
        if (active && expiry != null && expiry.isAfter(now)) {
            delay((expiry.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1))
            now = Instant.now()
        }
    }
    val decoration = avatarDecoration(shown, presentation, now)
    val shape = if (shown?.isSiteOwner == true) RoundedCornerShape(percent = 10) else CircleShape
    val pixels = with(LocalDensity.current) { size.roundToPx().coerceIn(24, 512) }
    val staticUrl = shown?.avatar?.replace("{size}", pixels.toString())?.let(::mediaUrl)
    val animatedUrl = shown?.animatedAvatar?.let(::mediaUrl)
    val label = shown?.let { "${it.displayName}的头像" } ?: "用户头像"
    Box(modifier.size(size).onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.width > 0f && bounds.height > 0f
    }.semantics { contentDescription = label }
        .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))) {
        AvatarImage(animatedUrl ?: staticUrl, fallbackUrl = staticUrl, pixels = pixels, animate = active,
            modifier = Modifier.fillMaxSize().clip(shape).background(LogoYellow.copy(alpha = 0.22f))) {
            Text(shown?.displayName?.take(1)?.uppercase() ?: "DO", Modifier.clearAndSetSemantics {},
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
        }
        if (shown != null && decoration != null) {
            AvatarBadge(shown.displayName, decoration, (size * 0.44f).coerceIn(14.dp, 30.dp), active,
                Modifier.align(Alignment.BottomEnd).offset(x = size * 0.06f, y = size * 0.06f))
        }
    }
}

@Composable
internal fun MemberNameDecorations(member: Member, modifier: Modifier = Modifier) {
    if (member.flair == null && member.status == null) return
    var now by remember(member.status) { mutableStateOf(Instant.now()) }
    LaunchedEffect(member.status) {
        now = Instant.now()
        val expiry = member.status?.endsAt
        if (expiry != null && expiry.isAfter(now)) {
            delay((expiry.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1))
            now = Instant.now()
        }
    }
    val decorations = memberNameDecorations(member, now)
    if (decorations.isEmpty()) return
    Row(modifier.testTag("member-name-decorations-${member.id}"), horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        decorations.forEach { AvatarBadge(member.displayName, it, 16.dp, true, Modifier) }
    }
}

@Composable
private fun AvatarBadge(name: String, decoration: AvatarDecoration, size: Dp, animate: Boolean, modifier: Modifier) {
    val metadata = LocalAvatarMetadata.current
    val status = (decoration as? AvatarDecoration.Status)?.value
    var emojiUrl by remember(status?.emoji, metadata) { mutableStateOf(status?.emoji?.let(AvatarMetadataCache::standardEmojiUrl)) }
    LaunchedEffect(status?.emoji, metadata) {
        if (status != null && metadata != null) emojiUrl = metadata.emojiUrl(status.emoji)
    }
    val label = when (decoration) {
        is AvatarDecoration.Flair -> "${name}的资质：${decoration.value.name.ifBlank { "用户资质" }}"
        is AvatarDecoration.Status -> "${name}的状态：${decoration.value.description.ifBlank { "自定义状态" }}"
    }
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    Box(modifier.size(size).semantics { contentDescription = label }
        .background(MaterialTheme.colorScheme.surface, CircleShape).padding(1.25.dp), contentAlignment = Alignment.Center) {
        when (decoration) {
            is AvatarDecoration.Status -> AvatarImage(emojiUrl?.let(::mediaUrl), pixels = pixels, animate = animate,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Rounded.Mood, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.primary)
            }
            is AvatarDecoration.Flair -> {
                val flair = decoration.value
                val background = avatarColor(flair.background)
                val foreground = avatarColor(flair.foreground) ?: MaterialTheme.colorScheme.onSurface
                val hasImage = flair.source.startsWith("/") || flair.source.startsWith("https://", ignoreCase = true)
                Box(Modifier.fillMaxSize().then(if (background != null) Modifier.clip(CircleShape).background(background) else Modifier), contentAlignment = Alignment.Center) {
                    if (hasImage) AvatarImage(mediaUrl(flair.source), pixels = pixels, animate = animate,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Rounded.Verified, null, Modifier.fillMaxSize(), tint = foreground)
                    }
                    else Icon(if (flair.source == "battery-quarter") BatteryQuarter else Icons.Rounded.Verified,
                        null, Modifier.fillMaxSize().padding(2.dp), tint = foreground)
                }
            }
        }
    }
}

/** CSS short hex and RGBA colors differ from Android's #AARRGGBB convention. */
internal fun avatarColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    if (!hex.matches(Regex("[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8}"))) return null
    val expanded = if (hex.length <= 4) hex.flatMap { listOf(it, it) }.joinToString("") else hex
    val number = expanded.toLong(16)
    val argb = if (expanded.length == 6) 0xff000000L or number else ((number and 255) shl 24) or (number ushr 8)
    return Color(argb)
}

// Font Awesome Free 6.7.2, © 2024 Fonticons, Inc., CC BY 4.0.
// Original path: svgs/solid/battery-quarter.svg. Full license is in assets/licenses.
private val BatteryQuarter: ImageVector by lazy {
    ImageVector.Builder("battery-quarter", 24.dp, (24f * 512 / 576).dp, 576f, 512f)
        .addPath(PathParser().parsePathString("M464 160c8.8 0 16 7.2 16 16l0 160c0 8.8-7.2 16-16 16L80 352c-8.8 0-16-7.2-16-16l0-160c0-8.8 7.2-16 16-16l384 0zM80 96C35.8 96 0 131.8 0 176L0 336c0 44.2 35.8 80 80 80l384 0c44.2 0 80-35.8 80-80l0-16c17.7 0 32-14.3 32-32l0-64c0-17.7-14.3-32-32-32l0-16c0-44.2-35.8-80-80-80L80 96zm112 96l-96 0 0 128 96 0 0-128z").toNodes(), fill = SolidColor(Color.Black)).build()
}
