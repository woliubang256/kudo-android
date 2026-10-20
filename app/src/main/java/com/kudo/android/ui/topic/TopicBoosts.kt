package com.kudo.android.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import com.kudo.android.data.*
import com.kudo.android.ui.components.*

@Composable
internal fun BoostForm(raw: String, change: (String) -> Unit, state: TopicState, sending: Boolean,
    send: () -> Unit, close: () -> Unit, retry: () -> Unit, username: String) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val names = state.boostEmojis
    val count = remember(raw, names) { BoostRules.count(raw, names.orEmpty()) }
    val limitError = count.problem.takeIf { count.length > 16 || count.emojis > 5 }
    val ready = names != null && count.problem == null && !sending
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus(); keyboard?.show() }
    Column(Modifier.fillMaxWidth().testTag("boost-form"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Boost", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            CommunityIconButton(Icons.Rounded.Close, "关闭 Boost", close, enabled = !sending)
        }
        CommunityTextField(raw, change, Modifier.fillMaxWidth().heightIn(min = 80.dp).focusRequester(focus).testTag("boost-input"),
            placeholder = if (username.isBlank()) "boost" else "boost $username", enabled = !sending, maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (ready) send() }))
        if (state.boostRulesError != null) {
            Text(state.boostRulesError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = retry) { Text("重试") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (names == null) "正在读取表情规则…" else "${count.length}/16字",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (limitError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            CommunityButton(if (sending) "发送中…" else "发送", send, enabled = ready, compact = true)
        }
        if (names != null && limitError != null) Text(limitError, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun PostBoosts(post: Post, enabled: Boolean, user: (String) -> Unit, openLink: (String) -> Unit) {
    if (post.boosts.isEmpty()) return
    var expanded by rememberSaveable(post.id) { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val handler = remember(openLink) { object : UriHandler { override fun openUri(uri: String) = openLink(uri) } }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        BoostFlow(Modifier.fillMaxWidth().testTag("post-boosts-${post.number}"), post.boosts.size, expanded, { expanded = !expanded }) { index ->
            val boost = post.boosts[index]
            val article = remember(boost.cooked, color) { parseInlineArticle(boost.cooked, color) }
            val emojis = article.emojis.mapValues { (_, emoji) ->
                InlineTextContent(Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
                    SiteEmoji(emoji.name, Modifier.fillMaxSize(), emoji.url)
                }
            }
            AvatarProfileLink(boost.author, enabled, { user(boost.author.username) }) { open ->
            Row(Modifier.heightIn(min = 40.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .055f), RoundedCornerShape(20.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = open)
                .padding(horizontal = 8.dp, vertical = 7.dp).testTag("boost-${boost.id}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Avatar(boost.author, 22.dp, presentation = AvatarPresentation.PLAIN)
                Text(article.text, Modifier.weight(1f, fill = false), inlineContent = emojis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            }
        }
    }
}

@Composable
private fun BoostFlow(modifier: Modifier, count: Int, expanded: Boolean, toggle: () -> Unit, content: @Composable (Int) -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val gap = 8.dp.roundToPx()
        val rowGap = 4.dp.roundToPx()
        val itemConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val width = constraints.maxWidth
        fun List<Pair<Int, Placeable>>.rowWidth() = sumOf { it.second.width } + gap * (size - 1).coerceAtLeast(0)
        val rows = mutableListOf(mutableListOf<Pair<Int, Placeable>>())
        for (index in 0 until count) {
            // Collapsed measurement slots have no accessible content and are never placed.
            val chip = subcompose(index to !expanded) {
                if (expanded) content(index) else Box(Modifier.clearAndSetSemantics {}) { content(index) }
            }.single().measure(itemConstraints)
            if (rows.last().isNotEmpty() && rows.last().rowWidth() + gap + chip.width > width) rows.add(mutableListOf())
            rows.last().add(index to chip)
            if (!expanded && rows.size > 2) break
        }
        val overflow = rows.size > 2
        val arrow = if (overflow) subcompose("toggle") {
            IconButton(toggle, Modifier.size(48.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
                Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    if (expanded) "收起 Boost" else "展开全部 Boost", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }.single().measure(itemConstraints) else null
        val visible = (if (expanded) rows else rows.take(2)).map { it.toMutableList() }.toMutableList()
        var narrowItem: Int? = null
        if (arrow != null) {
            fun fits() = visible.last().rowWidth() + (if (visible.last().isEmpty()) 0 else gap) + arrow.width <= width
            if (expanded) {
                if (!fits()) visible.add(mutableListOf())
            } else {
                while (!fits() && visible.last().size > 1) visible.last().removeAt(visible.last().lastIndex)
                if (!fits()) narrowItem = visible.last().first().first
            }
        }
        if (!expanded) visible.forEach { row ->
            for (index in row.indices) {
                val item = row[index].first
                val bounds = if (item == narrowItem) itemConstraints.copy(maxWidth = (width - arrow!!.width - gap).coerceAtLeast(0)) else itemConstraints
                row[index] = item to subcompose(item to false) { content(item) }.single().measure(bounds)
            }
        }
        val heights = visible.mapIndexed { index, row ->
            maxOf(row.maxOfOrNull { it.second.height } ?: 0, if (index == visible.lastIndex) arrow?.height ?: 0 else 0)
        }
        val height = constraints.constrainHeight(heights.sum() + rowGap * (visible.size - 1).coerceAtLeast(0))
        layout(width, height) {
            var y = 0
            visible.forEachIndexed { index, row ->
                var x = 0
                row.forEach { (_, chip) ->
                    chip.placeRelative(x, y + (heights[index] - chip.height) / 2)
                    x += chip.width + gap
                }
                if (index == visible.lastIndex) arrow?.placeRelative(width - arrow.width, y + (heights[index] - arrow.height) / 2)
                y += heights[index] + rowGap
            }
        }
    }
}
