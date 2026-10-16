package com.kudo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kudo.android.data.*
import com.kudo.android.media.imageLoadErrorText
import com.kudo.android.ui.account.LocalWebVerification
import com.kudo.android.ui.theme.Ink
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val trailingCountdown = Regex("""[0-9]+([^0-9]*)$""")

private fun rateLimitSubtitle(message: String, remaining: Int): String {
    val match = trailingCountdown.find(message)
    if (match == null) {
        return "$message\n\u8bf7\u5728 $remaining \u79d2\u540e\u91cd\u8bd5"
    }
    val prefix = message.substring(0, match.range.first)
    val suffix = match.groupValues[1]
    return if (remaining > 0) "$prefix$remaining$suffix"
    else "$prefix\u6b63\u5728\u91cd\u65b0\u52a0\u8f7d\u2026"
}

fun mediaUrl(value: String): String? {
    val url = "https://linux.do/".toHttpUrlOrNull()?.resolve(value.replace("{size}", "120")) ?: return null
    return url.takeIf { it.isHttps && value.isNotBlank() }?.toString()
}

@Composable
fun PageTopBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    CompositionLocalProvider(LocalToolbarActions provides true) {
        Row(Modifier.fillMaxWidth().then(if (LocalToolbarBackdrop.current == null) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier)
            .statusBarsPadding().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, icon: ImageVector = Icons.Rounded.Forum, actionLabel: String? = null, action: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Box(Modifier.size(68.dp).background(LogoYellow.copy(alpha = 0.12f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(31.dp), MaterialTheme.colorScheme.primary) }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null) Button(onClick = action, colors = ButtonDefaults.buttonColors(containerColor = LogoYellow, contentColor = Ink)) { Text(actionLabel) }
    }
}

@Composable
fun ErrorState(error: Throwable, retry: () -> Unit, login: () -> Unit, back: (() -> Unit)? = null) {
    val kind = (error as? ApiFailure)?.kind
    if (kind == FailureKind.NOT_FOUND || kind == FailureKind.FORBIDDEN) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.LinkOff, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.outline)
            Text(if (kind == FailureKind.NOT_FOUND) "内容不存在或暂无权限" else "暂无访问权限", style = MaterialTheme.typography.titleMedium)
            Text(if (kind == FailureKind.NOT_FOUND) "链接可能已失效，内容已删除，或当前账号没有访问权限。" else error.message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (back != null) CommunityButton("返回上一页", back, compact = true)
            Text("刷新登录状态", Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = login).padding(10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val auth = kind == FailureKind.SIGN_IN || kind == FailureKind.VERIFICATION
    val verification = LocalWebVerification.current
    val action = when (kind) {
        FailureKind.VERIFICATION -> { { if (verification != null) verification(retry) else login() } }
        FailureKind.SIGN_IN -> login
        else -> retry
    }
    val rateLimitRetryAfter = (error as? ApiFailure)
        ?.takeIf { it.kind == FailureKind.RATE_LIMIT }
        ?.retryAfter?.takeIf { it > 0 }
    if (rateLimitRetryAfter != null) {
        var remaining by remember(error) { mutableIntStateOf(rateLimitRetryAfter) }
        var retryStarted by remember(error) { mutableStateOf(false) }
        val triggerRetry = {
            if (!retryStarted) {
                retryStarted = true
                retry()
            }
        }
        LaunchedEffect(error) {
            repeat(rateLimitRetryAfter) {
                delay(1_000)
                remaining = (remaining - 1).coerceAtLeast(0)
            }
            triggerRetry()
        }
        EmptyState(
            "\u6682\u65f6\u6ca1\u80fd\u52a0\u8f7d",
            rateLimitSubtitle(error.message.orEmpty(), remaining),
            Icons.Rounded.CloudOff,
            "\u91cd\u65b0\u52a0\u8f7d",
            triggerRetry,
        )
        return
    }
    EmptyState(if (kind == FailureKind.VERIFICATION) "和社区连接，只差一步" else if (kind == FailureKind.SIGN_IN) "登录后继续" else "暂时没能加载",
        error.message ?: "请稍后重试", if (auth) Icons.Rounded.VerifiedUser else Icons.Rounded.CloudOff,
        if (kind == FailureKind.VERIFICATION) "完成网页验证" else if (auth) "登录 Linux.do" else "重新加载", action)
}

@Composable
fun LoadingRow() { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CommunityLoadingIndicator() } }

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        trailing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun TopicCard(topic: Topic, category: Category?, onClick: () -> Unit, onUser: (String) -> Unit) {
    if (topic.pinned && com.kudo.android.ui.theme.LocalDisplaySettings.current.compactPinnedTopics) {
        CompactPinnedTopicRow(topic, category, onClick)
        return
    }
    val resolvedCategory = category ?: LocalTopicPresentation.current.categories.firstOrNull { it.id == topic.categoryId }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
        .clickable(onClick = onClick)
        .testTag("topic-card-${topic.id}").padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(topic.author, 35.dp) { topic.author?.username?.let(onUser) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(topic.author?.displayName ?: "Linux.do 社区", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                TopicAuthorMetadata(topic.bumpedAt, resolvedCategory)
            }
            if (topic.pinned) Icon(Icons.Rounded.PushPin, "置顶", Modifier.size(17.dp), MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(topic.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            TopicUnreadBadge(topic)
        }
        if (topic.excerpt.isNotBlank()) Text(topic.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        mediaUrl(topic.image)?.let { TopicPreviewImage(topic.id, it, onClick) }
        if (topic.tagDetails.isNotEmpty()) TopicTags(topic.tagDetails)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(Icons.Rounded.Visibility, compactNumber(topic.views))
            Stat(Icons.Rounded.ChatBubbleOutline, compactNumber(topic.replies))
            Stat(Icons.Rounded.FavoriteBorder, compactNumber(topic.likes))
        }
    }
}

@Composable
private fun TopicPreviewImage(topicId: Int, url: String, openTopic: () -> Unit) {
    var decodedRatio by remember(url) { mutableStateOf<Float?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var failureText by remember(url) { mutableStateOf<String?>(null) }
    val failed = failureText != null
    var retry by remember(url) { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth().heightIn(max = 190.dp).aspectRatio(1.9f)
        .testTag("topic-image-$topicId").clip(RoundedCornerShape(10.dp))
        .then(if (loading || failed) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) else Modifier)
        .clickable(onClickLabel = if (failed) "重新加载图片" else "进入帖子详情") {
            if (failed) { loading = true; failureText = null; retry++ } else openTopic()
        }) {
        key(url, retry) { AsyncImage(url, if (failed) "话题图片加载失败，点击重试" else "话题图片，点击进入帖子详情",
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onLoading = { loading = true; failureText = null; decodedRatio = null },
            onError = { loading = false; failureText = imageLoadErrorText(it.result.throwable); decodedRatio = null },
            onSuccess = {
                loading = false
                failureText = null
                val decoded = it.result.image
                if (decoded.width > 0 && decoded.height > 0) decodedRatio = decoded.width.toFloat() / decoded.height
            }) }
        if (loading) CommunityLoadingIndicator(Modifier.align(Alignment.Center).size(24.dp).testTag("topic-image-$topicId-loading"))
        failureText?.let { details ->
            Column(Modifier.align(Alignment.Center).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("加载失败，点击重试", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Text(details, Modifier.testTag("topic-image-$topicId-error"), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        ImageAspectBadge(decodedRatio)
    }
}

internal fun imageAspectLabel(ratio: Float?): String? = when {
    ratio == null || !ratio.isFinite() || ratio <= 0f -> null
    ratio >= 3f -> "宽图"
    ratio <= 1f / 3f -> "长图"
    else -> null
}

@Composable
internal fun BoxScope.ImageAspectBadge(ratio: Float?) {
    val label = imageAspectLabel(ratio) ?: return
    Box(Modifier.matchParentSize().padding(6.dp)) {
        Text(label, Modifier.align(Alignment.BottomEnd).testTag("image-aspect-badge")
            .background(LogoYellow.copy(alpha = 0.9f), RoundedCornerShape(2.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
            color = Ink, fontSize = 12.sp, lineHeight = 16.sp,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
    }
}

@Composable
fun Stat(icon: ImageVector, text: String) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Icon(icon, null, Modifier.size(17.dp), MaterialTheme.colorScheme.onSurfaceVariant); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
