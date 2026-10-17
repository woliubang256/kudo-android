package com.kudo.android.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kudo.android.data.AvatarMetadataCache
import kotlin.math.roundToInt

private val titleEmojiPattern = Regex(":([A-Za-z0-9_+\\-]+(?::t[2-6])?):")

internal data class TopicTitleEmoji(val name: String, val url: String)
internal data class TopicTitleContent(val text: AnnotatedString, val emojis: Map<String, TopicTitleEmoji>)

internal fun parseTopicTitle(title: String, urls: Map<String, String>, failedUrls: Set<String> = emptySet()): TopicTitleContent {
    val emojis = linkedMapOf<String, TopicTitleEmoji>()
    val text = buildAnnotatedString {
        var cursor = 0
        titleEmojiPattern.findAll(title).forEach { match ->
            val name = match.groupValues[1]
            val url = urls[name]?.let(::mediaUrl)?.takeUnless { it in failedUrls } ?: return@forEach
            append(title.substring(cursor, match.range.first))
            val id = "title-emoji-$name"
            emojis[id] = TopicTitleEmoji(name, url)
            appendInlineContent(id, match.value)
            cursor = match.range.last + 1
        }
        append(title.substring(cursor))
    }
    return TopicTitleContent(text, emojis)
}

@Composable
internal fun TopicTitleText(
    title: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current,
) {
    val resolvedStyle = LocalTextStyle.current.merge(style).merge(TextStyle(fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight))
    val names = remember(title) { titleEmojiPattern.findAll(title).map { it.groupValues[1] }.toSet() }
    if (names.isEmpty()) {
        Text(title, modifier, style = resolvedStyle, maxLines = maxLines, overflow = overflow)
        return
    }
    val metadata = LocalAvatarMetadata.current
    var urls by remember(names, metadata) { mutableStateOf(emptyMap<String, String>()) }
    var failedUrls by remember(title, metadata) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(names, metadata) {
        val directory = metadata?.emojiDirectory().orEmpty().flatMap { it.items }.associate { it.name to it.url }
        urls = names.mapNotNull { name ->
            (directory[name] ?: AvatarMetadataCache.standardEmojiUrl(name))?.let { name to it }
        }.toMap()
    }
    val content = remember(title, urls, failedUrls) { parseTopicTitle(title, urls, failedUrls) }
    val emojiFontSize = resolvedStyle.fontSize.takeIf { it.isSp } ?: 16.sp
    val pixels = with(LocalDensity.current) { (emojiFontSize.toPx() * 1.2f).roundToInt().coerceAtLeast(1) }
    val inlineContent = content.emojis.mapValues { (_, emoji) ->
        InlineTextContent(Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
            AvatarImage(emoji.url, pixels, animate = true, cacheKey = "emoji:${emoji.url}",
                modifier = Modifier.fillMaxSize().semantics { contentDescription = ":${emoji.name}:" },
                contentScale = ContentScale.Fit,
                onLoaded = { loaded -> if (!loaded) failedUrls = failedUrls + emoji.url },
                placeholder = {})
        }
    }
    Text(content.text, modifier, style = resolvedStyle, maxLines = maxLines, overflow = overflow, inlineContent = inlineContent)
}
