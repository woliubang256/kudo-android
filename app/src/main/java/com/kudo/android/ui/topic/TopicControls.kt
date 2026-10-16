package com.kudo.android.ui.topic

import com.kudo.android.ui.components.TopBarIconButton
import com.kudo.android.ui.components.TopBarTextButton

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.*
import com.kudo.android.ui.components.Avatar
import com.kudo.android.ui.components.AvatarPresentation
import com.kudo.android.ui.components.CategoryIcons
import com.kudo.android.ui.components.MemberNameDecorations
import com.kudo.android.ui.components.TopicAuthorMetadata
import com.kudo.android.ui.components.liquid.LiquidButton
import com.kudo.android.ui.theme.LogoYellow
import com.kudo.android.ui.theme.LikeRed
import com.kudo.android.ui.theme.Ink
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import kotlin.math.roundToInt

@Composable
internal fun TopicTopBar(
    state: TopicState, enabled: Boolean, back: () -> Unit, user: (String) -> Unit,
    follow: () -> Unit, more: () -> Unit,
) {
    val author = state.detail?.author
    val profile = state.authorProfile
    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp).padding(horizontal = 8.dp, vertical = 6.dp)
        .testTag("topic-top-bar"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TopBarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", back, enabled = enabled)
        if (author == null) Text("话题详情", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        else Row(Modifier.weight(1f).heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button) { user(author.username) }.testTag("topic-author"),
            verticalAlignment = Alignment.CenterVertically) {
            Avatar(author, 32.dp, presentation = AvatarPresentation.PUBLIC)
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(author.displayName, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
                    MemberNameDecorations(author)
                }
                TopicAuthorMetadata(state.detail.originalPost?.updatedAt ?: state.detail.createdAt,
                    category = null, edited = state.detail.originalPost?.edited == true,
                    history = state.detail.originalPost?.takeIf { enabled && it.hasEditHistory }?.let { post ->
                        val actions = LocalTopicActions.current
                        { actions.history(state.detail.id, post) }
                    })
            }
        }
        if (author != null && author.id != state.viewerId &&
            (state.viewerId == null || profile == null || profile.canFollow || profile.isFollowed)) {
            val canClick = enabled && !state.loading && !state.followBusy && (state.viewerId == null || !state.authorLoading)
            TopBarTextButton(if (state.authorError != null && state.viewerId != null) "重试" else if (profile?.isFollowed == true) "已关注" else "关注",
                follow, Modifier.testTag("topic-follow"), enabled = canClick)
        }
        TopBarIconButton(Icons.Rounded.MoreVert, "更多", more, enabled = enabled && state.detail != null)
    }
}

@Composable
internal fun TopicReactionButton(
    post: Post?, enabled: Boolean, choose: (() -> Unit)?, click: () -> Unit,
    modifier: Modifier = Modifier, bottom: Boolean = false,
) {
    val reaction = post?.activeReaction
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f * if (enabled) 1f else .38f)
    val label = if (bottom) {
        if (reaction != null && reaction != "heart") "取消主帖回应" else "赞主帖"
    } else "回应此楼层"
    Row(modifier.clip(RoundedCornerShape(50))
        .combinedClickable(enabled = enabled, role = Role.Button,
            onClickLabel = if (reaction == null) "点赞" else "取消回应", onClick = click,
            onLongClickLabel = "选择回应", onLongClick = choose)
        .heightIn(min = 48.dp).widthIn(min = 48.dp)
        .semantics(mergeDescendants = true) {
            contentDescription = label
            stateDescription = when {
                reaction == null -> "未回应"
                post?.canReact == false -> "已回应 $reaction，无法撤销"
                reaction == "heart" -> "已点赞，短按取消"
                else -> "已回应 $reaction，短按取消"
            }
        }.padding(horizontal = if (bottom) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        val size = if (bottom) 22.dp else 19.dp
        if (reaction != null && reaction != "heart") SiteEmoji(reaction, Modifier.size(size))
        else Icon(if (reaction == "heart") Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null,
            Modifier.size(size), tint = if (reaction == "heart") LikeRed else inactiveColor)
        if (!bottom) {
            Spacer(Modifier.width(5.dp))
            Text(post?.reactionCount?.toString().orEmpty(), style = MaterialTheme.typography.labelLarge,
                color = if (reaction == null) inactiveColor else LocalContentColor.current)
        }
    }
}

@Composable
internal fun TopicBottomBar(
    state: TopicState, backdrop: Backdrop, glass: GlassMode, enabled: Boolean,
    reply: () -> Unit, like: (Post) -> Unit, bookmark: (Post) -> Unit, share: () -> Unit,
    choose: (Post) -> Unit, modifier: Modifier = Modifier,
    boost: (Post) -> Unit = {},
    currentFloor: Int = 1, floors: () -> Unit = {},
    floorEditing: Boolean = false, floorRaw: String = currentFloor.toString(),
    floorChange: (String) -> Unit = {}, floorHighest: Int = 1,
    floorJumping: Boolean = false, floorError: String? = null, floorConfirm: (Int) -> Unit = {},
    floorClose: () -> Unit = {},
) {
    val detail = state.detail ?: return
    val first = detail.originalPost
    val actions = LocalTopicActions.current
    val ownFirst = first?.let { state.viewerId != null && it.author.id == state.viewerId } == true
    val available = enabled && !floorEditing && !state.loading && first != null && first.id !in state.busy
    BoxWithConstraints(modifier.navigationBarsPadding().then(if (floorEditing) Modifier.imePadding() else Modifier)
        .padding(horizontal = 16.dp, vertical = 20.dp).fillMaxWidth()) {
    val compact = maxWidth < 320.dp || (LocalDensity.current.fontScale > 1.2f && maxWidth < 360.dp) || (detail.highestPostNumber >= 10000 && maxWidth < 380.dp)
    val replying = state.replyingUsers
    val floor = currentFloor.coerceIn(1, detail.highestPostNumber)
    val fullWidth = maxWidth
    val transition = updateTransition(floorEditing, label = "floor-controls")
    val expansion by transition.animateFloat(transitionSpec = { tween(280, easing = FastOutSlowInEasing) }, label = "expansion") {
        if (it) 1f else 0f
    }
    Layout(content = {
        Box(Modifier.graphicsLayer { alpha = 1f - expansion }
            .then(if (floorEditing) Modifier.clearAndSetSemantics {} else Modifier)) {
            LiquidButton(onClick = reply, enabled = enabled && !floorEditing && !detail.closed, backdrop = backdrop, mode = glass,
                surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
                modifier = Modifier.testTag("topic-reply").semantics {
                    contentDescription = "写回复"
                    if (replying.isNotEmpty()) stateDescription = "${replying.size}人正在回复"
                }) {
                if (compact) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DriveFileRenameOutline, null,
                            Modifier.size(20.dp).alpha(if (replying.isEmpty()) 1f else 0f).testTag("topic-reply-icon"))
                        if (replying.isNotEmpty()) ReplyingAvatars(replying, true, Modifier.requiredSize(40.dp, 20.dp))
                    }
                } else {
                    Icon(Icons.Outlined.DriveFileRenameOutline, null, Modifier.size(20.dp).testTag("topic-reply-icon"))
                    Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
                        Text("写回复", if (replying.isEmpty()) Modifier else Modifier.alpha(0f).clearAndSetSemantics {},
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal, maxLines = 1)
                        if (replying.isNotEmpty()) ReplyingAvatars(replying, false, Modifier.matchParentSize())
                    }
                }
            }
        }
        Box(Modifier.testTag("floor-control-slot").graphicsLayer {
            clip = transition.isRunning
            shape = Capsule()
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - expansion }
                .then(if (floorEditing) Modifier.clearAndSetSemantics {} else Modifier)) {
                LiquidButton(onClick = floors, enabled = enabled && !floorEditing && !state.loading, backdrop = backdrop, mode = glass,
                    surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .45f),
                    progress = floor.toFloat() / detail.highestPostNumber, progressColor = LogoYellow.copy(alpha = .27f),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("topic-floor-index").semantics {
                        contentDescription = "楼层索引，当前第 $floor 楼，共 ${detail.highestPostNumber} 楼"
                        progressBarRangeInfo = ProgressBarRangeInfo(floor.toFloat(), 1f..detail.highestPostNumber.toFloat())
                    }) {
                    BasicText("$floor / ${detail.highestPostNumber}", style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = .5.sp))
                }
            }
            if (transition.currentState || transition.targetState) Box(Modifier.requiredWidth(fullWidth).graphicsLayer { alpha = expansion }) {
                FloorNavigationBar(floorRaw, floorChange, floorHighest, floorJumping, floorError, floorConfirm,
                    backdrop, glass, Modifier.fillMaxWidth(), openingFloor = currentFloor, close = floorClose)
            }
        }
        Box(Modifier.graphicsLayer { alpha = 1f - expansion }
            .then(if (floorEditing) Modifier.clearAndSetSemantics {} else Modifier)) {
            LiquidButton(onClick = null, backdrop = backdrop, mode = glass,
                surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.testTag("topic-actions")) {
                TopicReactionButton(first, available && (state.viewerId == null || first?.canReact == true),
                    choose = if (first?.reactionsAvailable == true && detail.validReactions.isNotEmpty()) ({ choose(first) }) else null,
                    click = { first?.let(like) }, bottom = true)
                if (ownFirst) {
                    IconButton(onClick = { first?.let { actions.edit(detail.id, it) } },
                        enabled = available && first?.editableBy(state.viewerId) == true, modifier = Modifier.testTag("topic-edit")) {
                        Icon(Icons.Rounded.Edit, "编辑主帖", Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f * LocalContentColor.current.alpha))
                    }
                } else {
                    IconButton(onClick = { first?.let(boost) }, enabled = available && (state.viewerId == null || first?.canBoost == true)) {
                        Icon(CategoryIcons.get("rocket"), "Boost 主帖", Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f * LocalContentColor.current.alpha))
                    }
                }
            }
        }
    }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val left = measurables[0].measure(loose)
        val right = measurables[2].measure(loose)
        val progress = expansion.coerceIn(0f, 1f)
        val gap = 8.dp.roundToPx()
        val start = ((left.width + gap) * (1f - progress)).roundToInt()
        val end = constraints.maxWidth - ((right.width + gap) * (1f - progress)).roundToInt()
        val middleWidth = (end - start).coerceAtLeast(1)
        val middle = measurables[1].measure(loose.copy(minWidth = middleWidth, maxWidth = middleWidth))
        val height = maxOf(left.height, middle.height, right.height)
        layout(constraints.maxWidth, height) {
            left.placeRelative(start - gap - left.width, (height - left.height) / 2)
            middle.placeRelative(start, (height - middle.height) / 2)
            right.placeRelative(end + gap, (height - right.height) / 2)
        }
    }
    }
}

@Composable
private fun ReplyingAvatars(users: List<Member>, compact: Boolean, modifier: Modifier) {
    val visible = users.take(3)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val size = minOf(if (compact) 20.dp else 24.dp, maxHeight, maxWidth)
    val step = if (visible.size == 1) 0.dp else minOf(if (compact) 10.dp else 16.dp,
        ((maxWidth - size) / (visible.size - 1)).coerceAtLeast(0.dp))
    Box(Modifier.width(size + step * (visible.size - 1)).height(size).testTag("topic-replying-avatars")) {
        visible.forEachIndexed { index, member ->
            Box(Modifier.offset(x = step * index).size(size).clip(CircleShape)
                .testTag("topic-replying-avatar-${member.id}")) {
                Avatar(member, size, presentation = AvatarPresentation.PLAIN)
                if (index == 2 && users.size > 3) {
                    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = .72f)), contentAlignment = Alignment.Center) {
                        BasicText("+${users.size - 3}", Modifier.padding(horizontal = 1.dp).testTag("topic-replying-overflow"),
                            style = MaterialTheme.typography.labelSmall.copy(color = Color.White, fontWeight = FontWeight.Normal),
                            maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 6.sp, maxFontSize = 10.sp, stepSize = .5.sp))
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun TopicReactionChoices(post: Post, choices: List<String>, enabled: Boolean, select: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { reaction ->
            val selected = post.activeReaction == reaction
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                .background(if (selected) LogoYellow.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                .clickable(enabled = enabled, role = Role.Button) { select(reaction) }
                .semantics(mergeDescendants = true) {
                    contentDescription = "回应 $reaction"
                    stateDescription = if (selected) "已选择，短按取消" else "未选择"
                }.testTag("reaction-choice-$reaction"), contentAlignment = Alignment.Center) {
                SiteEmoji(reaction, Modifier.size(28.dp))
            }
        }
    }
}
