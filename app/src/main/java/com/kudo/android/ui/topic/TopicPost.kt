package com.kudo.android.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import java.util.Locale

@Composable
internal fun TopicPost(post: Post, detail: TopicDetail, state: TopicState, enabled: Boolean,
    like: (Post) -> Unit, bookmark: (Post) -> Unit, reply: (Post?) -> Unit,
    user: (String) -> Unit, image: (String) -> Unit, openLink: (String) -> Unit, choose: (Post) -> Unit,
    originalRevealed: Boolean = false, revealOriginal: () -> Unit = {}, boost: (Post) -> Unit = {},
    vote: (Post, String, List<String>) -> Unit = { _, _, _ -> }, login: () -> Unit = {},
    onHeading: ((ArticleHeading, BringIntoViewRequester) -> Unit)? = null) {
    val ignoredUsers = LocalIgnoredUsers.current
    val historyActions = LocalTopicActions.current
    var revealed by remember(post.id, ignoredUsers) { mutableStateOf(false) }
    val ignored = post.author.username.lowercase(Locale.ROOT) in ignoredUsers
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).testTag("post-${post.number}")) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ignored && !(if (post.number == 1) originalRevealed else revealed)) {
            Text("@${post.author.username} 的内容已忽略", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { if (post.number == 1) revealOriginal() else revealed = true }) { Text("查看此楼层") }
        } else {
            if (post.number != 1) Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(enabled = enabled) { user(post.author.username) }, verticalAlignment = Alignment.CenterVertically) {
                    Avatar(post.author, 32.dp, presentation = AvatarPresentation.PUBLIC)
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(post.author.displayName, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            MemberNameDecorations(post.author)
                            if (post.author.id == detail.author?.id) Text("楼主", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        TopicAuthorMetadata(post.updatedAt, detail.categoryId.takeIf { post.number == 1 }?.let { categoryId ->
                            LocalTopicPresentation.current.categories.firstOrNull { it.id == categoryId }
                        }, post.edited, history = if (enabled && post.hasEditHistory) ({ historyActions.history(detail.id, post) }) else null)
                    }
                }
                PostUnreadIndicator(post, state)
                val actions = LocalTopicActions.current
                IconButton(onClick = { actions.openReply(detail.id, post, bookmark) }, enabled = enabled && !state.loading && post.id !in state.busy) {
                    Icon(Icons.Rounded.MoreHoriz, "更多操作", Modifier.size(20.dp))
                }
                }
            }
            PostReadingContent(post.number, enabled && !state.loading) {
                CompositionLocalProvider(LocalArticleHeadingCollector provides onHeading) {
                RichContent(post.cooked, image, openLink, galleryKey = "post-${post.id}", galleryBottomInset = 110.dp,
                    poll = { name ->
                        val poll = post.polls.firstOrNull { it.name == name }
                        val web = { openLink("https://linux.do/t/topic/${detail.id}/${post.number}") }
                        if (poll != null) PostPollView(poll, post.pollVotes[name].orEmpty(), post.author.id == state.viewerId,
                            detail.archived || !enabled, post.id in state.busy, state.viewerId != null,
                            { vote(post, name, it) }, login, web)
                        else Text("打开网页查看投票", Modifier.clickable(onClick = web), color = MaterialTheme.colorScheme.primary)
                    })
                }
            }
            UserSignature(post, openLink)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                val actions = LocalTopicActions.current
                TopicReactionButton(post, enabled && !state.loading && post.id !in state.busy && (state.viewerId == null || post.canReact),
                    choose = if (post.reactionsAvailable && detail.validReactions.isNotEmpty()) ({ choose(post) }) else null,
                    click = { like(post) })
                if (state.viewerId != null && post.author.id == state.viewerId) {
                    IconButton(onClick = { actions.edit(detail.id, post) },
                        enabled = enabled && !state.loading && post.id !in state.busy && post.editableBy(state.viewerId),
                        modifier = Modifier.testTag("post-edit-${post.number}")) {
                        Icon(Icons.Rounded.Edit, "编辑第 ${post.number} 楼", Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f * LocalContentColor.current.alpha))
                    }
                } else {
                    IconButton(onClick = { boost(post) }, enabled = enabled && !state.loading && post.id !in state.busy && (state.viewerId == null || post.canBoost)) {
                        Icon(CategoryIcons.get("rocket"), "Boost 此帖子", Modifier.size(17.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f * LocalContentColor.current.alpha))
                    }
                }
                TextButton(onClick = { reply(post) }, enabled = enabled && !detail.closed) { Text("回复") }
            }
            PostBoosts(post, enabled, user, openLink)
        }
    }
    if (post.number != 1 && (!ignored || revealed)) {
        val isSolution = post.isSolution || detail.solutions.any { it.postNumber == post.number }
        Row(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (isSolution) Icon(Icons.Rounded.CheckCircle, "解决方案",
                Modifier.size(14.dp).testTag("solution-marker-${post.number}"), tint = MaterialTheme.colorScheme.primary)
            Text("#${post.number}", Modifier.testTag("post-floor-number-${post.number}"),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))
        }
    }
    }
}

@Composable
internal fun TopicSolutionsSummary(detail: TopicDetail, posts: List<Post>, enabled: Boolean, navigate: (Int) -> Unit) {
    if (detail.solutions.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .42f), RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 12.dp).testTag("topic-solutions"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.CheckCircle, "解决方案", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text("解决方案", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        detail.solutions.forEach { solution ->
            val post = posts.firstOrNull { it.number == solution.postNumber }
            val author = solution.name.ifBlank { solution.username }.ifBlank { post?.author?.displayName.orEmpty() }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button,
                    onClickLabel = "跳转到解决方案 ${solution.postNumber} 楼") { navigate(solution.postNumber) }
                .testTag("topic-solution-${solution.postNumber}").padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("#${solution.postNumber}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                solution.excerpt.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun ReplyPreview(thread: PostThread, load: ReplyLoad?, hasMore: Boolean, enabled: Boolean, open: (Int?) -> Unit) {
    if (thread.replies.isEmpty() && !hasMore && load?.loading != true && load?.error == null) return
    val ignored = LocalIgnoredUsers.current
    val threadPosts = remember(thread) { (listOf(thread.root) + thread.replies).associateBy(Post::number) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
        .testTag("reply-preview-${thread.root.number}").padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        thread.replies.take(3).forEach { post ->
            val hidden = post.author.username.lowercase(Locale.ROOT) in ignored
            val images = remember(post.cooked) { parseArticle(post.cooked).articleImages() }
            val authorLabel = replyAuthorLabel(post, thread.root.number, threadPosts)
            Box(Modifier.fillMaxWidth().testTag("reply-preview-post-${post.number}")) {
                if (hidden) Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("$authorLabel：") }
                    append("内容已忽略")
                }, Modifier.fillMaxWidth().clickable(enabled = enabled) { open(post.number) }, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                else ReplyPreviewText(post, authorLabel, images, enabled) { open(post.number) }
            }
        }
        Text(if (load?.loading == true && thread.replies.isEmpty()) "正在加载回复…" else if (hasMore || thread.replies.size > 3) "查看更多回复" else "查看 ${thread.replies.size} 条回复",
            Modifier.fillMaxWidth().clickable(enabled = enabled) { open(null) }.padding(vertical = 3.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReplyPreviewText(post: Post, authorLabel: String, images: List<ArticleBlock.Image>, enabled: Boolean, open: () -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val style = MaterialTheme.typography.bodyMedium
    val textHtml = remember(post.cooked) {
        parseArticle(post.cooked).filterIsInstance<ArticleBlock.TextBlock>().joinToString("<br>") { it.html }
    }
    val article = remember(textHtml, linkColor) { parseInlineArticle(textHtml, linkColor) }
    val text = remember(article.text, authorLabel, linkColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = linkColor)) { append("$authorLabel：") }
            append(article.text.subSequence(0, article.text.text.trimEnd().length))
        }
    }
    val emojis = remember(article.emojis) { article.emojis.mapValues { (_, emoji) ->
        InlineTextContent(Placeholder(1.25.em, 1.25.em, PlaceholderVerticalAlign.TextCenter)) {
            SiteEmoji(emoji.name, Modifier.fillMaxSize(), emoji.url)
        }
    } }
    if (images.isEmpty()) {
        Text(text, Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = open),
            inlineContent = emojis, maxLines = 2, overflow = TextOverflow.Ellipsis, style = style)
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val label = "查看图片${if (images.size > 1) "(${images.size})" else ""}"
    val iconSize = with(density) { style.fontSize.toDp() * 1.15f }
    val gap = with(density) { style.fontSize.toDp() * .15f }
    val placeholder = remember(label, style, density, measurer) {
        val labelSize = measurer.measure(label, style, maxLines = 1).size
        with(density) {
            // 内联占位符和子布局分别取整，留出像素余量以免裁掉末尾数字。
            Placeholder((labelSize.width + iconSize.roundToPx() + gap.roundToPx() + 2).toSp(),
                maxOf(labelSize.height, iconSize.roundToPx()).toSp(), PlaceholderVerticalAlign.Center)
        }
    }
    val inline = emojis + (ReplyImageInlineId to InlineTextContent(placeholder) {
        ReplyPreviewImages(images, post.id, label, iconSize, gap, enabled, open)
    })
    val placeholders = inline.mapValues { it.value.placeholder }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val preview = remember(text, placeholders, style, width, measurer) {
            fitReplyPreviewImages(text, placeholders, style, width, measurer)
        }
        Text(preview, Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = open),
            inlineContent = inline, maxLines = 2, overflow = TextOverflow.Ellipsis, style = style)
    }
}

private const val ReplyImageInlineId = "reply-preview-image-action"

private fun fitReplyPreviewImages(text: AnnotatedString, placeholders: Map<String, Placeholder>,
    style: TextStyle, width: Int, measurer: TextMeasurer): AnnotatedString {
    fun candidate(length: Int): AnnotatedString = buildAnnotatedString {
        var end = length
        if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        end = text.text.substring(0, end).trimEnd().length
        append(text.subSequence(0, end))
        if (length < text.length) append("…")
        append(" ")
        appendInlineContent(ReplyImageInlineId, "\uFFFC")
    }
    fun fits(value: AnnotatedString): Boolean {
        val ranges = value.getStringAnnotations(0, value.length).mapNotNull { annotation ->
            placeholders[annotation.item]?.let { AnnotatedString.Range(it, annotation.start, annotation.end) }
        }
        val result = measurer.measure(value, style, maxLines = 2, constraints = Constraints(maxWidth = width),
            placeholders = ranges)
        return !result.hasVisualOverflow && result.placeholderRects.lastOrNull() != null
    }
    val full = candidate(text.length)
    if (fits(full)) return full
    var start = 0
    var end = text.length
    while (start < end) {
        val middle = (start + end + 1) / 2
        if (fits(candidate(middle))) start = middle else end = middle - 1
    }
    return candidate(start)
}

@Composable
private fun ReplyPreviewImages(images: List<ArticleBlock.Image>, postId: Int, label: String,
    iconSize: Dp, gap: Dp, enabled: Boolean, fallback: () -> Unit) {
    val gallery = LocalImageGallery.current
    Row(Modifier.fillMaxSize().clickable(enabled = enabled) {
        if (gallery != null) gallery.open(images, 0, "reply-preview-$postId") else fallback()
    }.testTag("reply-preview-images-${postId}"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap)) {
        Icon(Icons.Rounded.Image, "查看图片", Modifier.size(iconSize), tint = MaterialTheme.colorScheme.primary)
        Text(label,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            maxLines = 1)
    }
}

@Composable
internal fun ThreadPreviewPost(post: Post, enabled: Boolean, image: (String) -> Unit,
    openLink: (String) -> Unit, navigate: (Int) -> Unit, authorLabel: String, state: TopicState,
    like: (Post) -> Unit, reply: (Post) -> Unit, choose: (Post) -> Unit) {
    val hidden = post.author.username.lowercase(Locale.ROOT) in LocalIgnoredUsers.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("thread-preview-post-${post.number}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 10.dp)) { Avatar(post.author, 28.dp, presentation = AvatarPresentation.PUBLIC) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(authorLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("#${post.number}", Modifier.testTag("thread-floor-${post.number}"), maxLines = 1,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { navigate(post.number) }, enabled = enabled,
                    modifier = Modifier.testTag("thread-jump-${post.number}")) {
                    Icon(Icons.Rounded.OpenInNew, "跳到第 ${post.number} 楼", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (hidden) Text("内容已忽略", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else RichContent(post.cooked, image, openLink, galleryKey = "thread-preview-${post.id}")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(end = 4.dp).testTag("thread-metadata-${post.number}")) {
                    val historyActions = LocalTopicActions.current
                    TopicAuthorMetadata(post.updatedAt, null, post.edited,
                        history = if (enabled && post.hasEditHistory) ({ state.detail?.let { historyActions.history(it.id, post) } }) else null)
                }
                if (!hidden) {
                    val actions = LocalTopicActions.current
                    if (post.editableBy(state.viewerId)) IconButton(onClick = { state.detail?.let { actions.edit(it.id, post) } },
                        enabled = enabled && !state.loading && post.id !in state.busy, modifier = Modifier.testTag("thread-edit-${post.number}")) {
                        Icon(Icons.Rounded.Edit, "编辑第 ${post.number} 楼", Modifier.size(19.dp))
                    }
                    TopicReactionButton(post, enabled && !state.loading && post.id !in state.busy && (state.viewerId == null || post.canReact),
                        choose = if (post.reactionsAvailable && state.detail?.validReactions?.isNotEmpty() == true) ({ choose(post) }) else null,
                        click = { like(post) }, modifier = Modifier.testTag("thread-like-${post.number}"))
                    val canReply = enabled && state.detail?.closed == false && state.detail.archived == false
                    Row(Modifier.clip(RoundedCornerShape(50)).clickable(enabled = canReply, role = Role.Button,
                        onClickLabel = "回复第 ${post.number} 楼") { reply(post) }
                        .heightIn(min = 48.dp).widthIn(min = 48.dp).padding(horizontal = 8.dp)
                        .testTag("thread-reply-${post.number}"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Rounded.ChatBubbleOutline, "回复第 ${post.number} 楼", Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canReply) .75f else .38f))
                        if (post.replyCount > 0) Text(post.replyCount.toString(), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (canReply) .75f else .38f))
                    }
                }
            }
        }
    }
}

internal fun replyAuthorLabel(post: Post, rootNumber: Int, posts: Map<Int, Post>): String {
    val parent = post.replyTo?.takeIf { it > 1 && it < post.number && it != rootNumber }
        ?: return post.author.displayName
    val recipient = posts[parent]?.author?.displayName ?: "#$parent"
    return "${post.author.displayName} 回复 $recipient"
}
