package com.kudo.android.ui.topic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import coil3.compose.AsyncImage
import coil3.asDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kudo.android.data.AvatarMetadataCache
import com.kudo.android.ui.components.AvatarImage
import com.kudo.android.ui.components.ImageAspectBadge
import com.kudo.android.ui.components.CommunityLoadingIndicator
import com.kudo.android.ui.components.LocalAvatarMetadata
import com.kudo.android.ui.components.mediaUrl
import com.kudo.android.ui.LinuxDoLink
import com.kudo.android.ui.ExternalAppLinkPolicy
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.UUID

sealed interface ArticleBlock {
    data class TextBlock(
        val html: String,
        val quote: Boolean = false,
        val heading: Boolean = false,
        val headingLevel: Int = 0,
        val headingText: String = "",
    ) : ArticleBlock
    data class Quote(val blocks: List<ArticleBlock>, val author: String = "", val avatar: String? = null, val source: String? = null) : ArticleBlock
    data class Callout(val type: ArticleCalloutType, val title: String, val blocks: List<ArticleBlock>, val open: Boolean = true, val foldable: Boolean = true) : ArticleBlock
    data class Details(val summary: String, val blocks: List<ArticleBlock>, val open: Boolean = false) : ArticleBlock
    data class Spoiler(val blocks: List<ArticleBlock>) : ArticleBlock
    data class Code(val text: String) : ArticleBlock
    data class Table(val rows: List<Row>) : ArticleBlock {
        data class Row(val cells: List<String>, val header: Boolean)
    }
    data class ListBlock(val items: List<ArticleListItem>) : ArticleBlock
    data class Poll(val name: String) : ArticleBlock
    data class Image(
        val url: String, val alt: String, val ratio: Float? = null,
        val originalUrl: String = url, val filename: String = "",
        val dimensions: String = "", val sizeLabel: String = "",
        val displayWidth: Float? = null,
    ) : ArticleBlock
    data class Embed(val url: String, val title: String, val description: String = "", val image: String? = null, val site: String = "") : ArticleBlock
    data object Rule : ArticleBlock
}

data class ArticleListItem(val marker: String, val checked: Boolean?, val blocks: List<ArticleBlock>)

data class ArticleHeading(val level: Int, val title: String, val path: String = "")

internal val LocalArticleHeadingCollector = staticCompositionLocalOf<((ArticleHeading, BringIntoViewRequester) -> Unit)?> { null }

fun hasThemeTocMarker(html: String): Boolean {
    val first = Jsoup.parseBodyFragment(html).body().children().firstOrNull() ?: return false
    return first.normalName() == "div" && first.attr("data-theme-toc").equals("true", ignoreCase = true)
}

/** Extract headings from article content while keeping quoted material out of a post TOC. */
fun List<ArticleBlock>.articleHeadings(): List<ArticleHeading> = buildList {
    fun visit(blocks: List<ArticleBlock>, parent: String) {
        blocks.forEachIndexed { index, block ->
            val path = "$parent-$index"
            when (block) {
                is ArticleBlock.TextBlock -> if (block.heading && block.headingLevel in 1..6 && block.headingText.isNotBlank()) {
                    add(ArticleHeading(block.headingLevel, block.headingText, path))
                }
                is ArticleBlock.Callout -> visit(block.blocks, path)
                is ArticleBlock.Details -> visit(block.blocks, path)
                is ArticleBlock.Spoiler -> visit(block.blocks, path)
                is ArticleBlock.ListBlock -> block.items.forEachIndexed { item, value -> visit(value.blocks, "$path-item$item") }
                is ArticleBlock.Quote, is ArticleBlock.Code, is ArticleBlock.Table, is ArticleBlock.Image,
                is ArticleBlock.Embed, is ArticleBlock.Poll, ArticleBlock.Rule -> Unit
            }
        }
    }
    visit(this@articleHeadings, "root")
}

fun topicTocHeadings(html: String): List<ArticleHeading> =
    if (hasThemeTocMarker(html)) parseArticle(html).articleHeadings().filter { it.level == 2 || it.level == 3 }
    else emptyList()

data class ArticleCalloutType(val raw: String) {
    val label: String get() = raw.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }

    companion object {
        val NOTE = ArticleCalloutType("note")
        val ABSTRACT = ArticleCalloutType("abstract")
        val INFO = ArticleCalloutType("info")
        val TODO = ArticleCalloutType("todo")
        val TIP = ArticleCalloutType("tip")
        val SUCCESS = ArticleCalloutType("success")
        val QUESTION = ArticleCalloutType("question")
        val WARNING = ArticleCalloutType("warning")
        val CAUTION = ArticleCalloutType("caution")
        val FAILURE = ArticleCalloutType("failure")
        val DANGER = ArticleCalloutType("danger")
        val BUG = ArticleCalloutType("bug")
        val EXAMPLE = ArticleCalloutType("example")
        val QUOTE = ArticleCalloutType("quote")
        fun of(raw: String) = ArticleCalloutType(when (val value = raw.lowercase()) {
            "summary", "tldr" -> "abstract"
            "hint", "important" -> "tip"
            "check", "done" -> "success"
            "help", "faq" -> "question"
            "attention" -> "warning"
            "fail", "missing" -> "failure"
            "error" -> "danger"
            "cite" -> "quote"
            else -> value
        })
    }
}

private fun previewSource(element: Element, fallback: String): String {
    val width = element.attr("width").toFloatOrNull()?.takeIf { it.isFinite() && it > 0 }
    var bestUrl = fallback
    var bestDensity = 1f
    element.attr("srcset").split(',').forEach { candidate ->
        val parts = candidate.trim().split(Regex("\\s+"))
        if (parts.size !in 1..2) return@forEach
        val url = mediaUrl(parts[0]) ?: return@forEach
        val descriptor = parts.getOrNull(1)
        val density = when {
            descriptor == null -> 1f
            descriptor.endsWith('x') -> descriptor.dropLast(1).toFloatOrNull()
            descriptor.endsWith('w') && width != null -> descriptor.dropLast(1).toIntOrNull()?.div(width)
            else -> null
        } ?: return@forEach
        if (density.isFinite() && density > bestDensity) {
            bestUrl = url
            bestDensity = density
        }
    }
    return bestUrl
}

fun parseArticle(html: String): List<ArticleBlock> {
    val result = mutableListOf<ArticleBlock>()
    fun callout(element: Element): ArticleBlock.Callout? {
        val first = element.children().firstOrNull()?.takeIf { it.normalName() in setOf("p", "div") } ?: return null
        val lines = first.html().split(Regex("(?i)<br\\s*/?>"), limit = 2)
        val header = Jsoup.parseBodyFragment(lines[0]).text().trim()
        val match = Regex("^\\[!([A-Za-z][A-Za-z0-9_-]*)\\]([+-])?(?:\\s+(.+))?$").matchEntire(header) ?: return null
        val type = match.groupValues[1].lowercase()
        val title = match.groupValues[3].trim().ifBlank { type.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() } }
        val body = element.clone().apply {
            val paragraph = children().firstOrNull() ?: return null
            if (lines.size == 1) paragraph.remove() else paragraph.html(lines[1])
        }
        return ArticleBlock.Callout(ArticleCalloutType.of(type), title, parseArticle(body.html()),
            open = match.groupValues[2] != "-", foldable = match.groupValues[2].isNotEmpty())
    }
    fun walk(element: Element, quote: Boolean = false) {
        if (element.hasClass("footnotes-list") || element.hasClass("footnotes") || element.hasClass("footnotes-sep")) return
        if (element.hasClass("spoiler")) {
            if (element.normalName() == "div" || element.select("img:not(.emoji)").isNotEmpty()) {
                result.add(ArticleBlock.Spoiler(parseArticle(element.html()))); return
            }
            result.add(ArticleBlock.TextBlock(element.outerHtml(), quote)); return
        }
        if (element.hasClass("meta") && element.parents().any { it.hasClass("lightbox") || it.hasClass("lightbox-wrapper") }) return
        if (element.hasClass("poll") && element.hasAttr("data-poll-name")) {
            result.add(ArticleBlock.Poll(element.attr("data-poll-name"))); return
        }
        when (element.normalName()) {
            "script", "style", "noscript" -> Unit
            "pre" -> result.add(ArticleBlock.Code(element.wholeText()))
            "table" -> {
                val rows = element.select("tr").mapNotNull { row ->
                    val cells = row.children().filter { it.normalName() == "th" || it.normalName() == "td" }
                    if (cells.isEmpty()) null else ArticleBlock.Table.Row(cells.map { it.html() }, cells.any { it.normalName() == "th" })
                }
                if (rows.isNotEmpty()) result.add(ArticleBlock.Table(rows))
            }
            "ol", "ul" -> {
                val ordered = element.normalName() == "ol"
                val children = element.children().filter { it.normalName() == "li" }
                val step = if (element.hasAttr("reversed")) -1 else 1
                var number = element.attr("start").toLongOrNull() ?: if (step < 0) children.size.toLong() else 1L
                val items = children.map { child ->
                    if (ordered) child.attr("value").toLongOrNull()?.let { number = it }
                    val marker = if (ordered) "$number." else "•"
                    number += step
                    val copy = child.clone()
                    val checkbox = copy.select(".chcklst-box, input[type=checkbox]").firstOrNull {
                        it.parents().firstOrNull { parent -> parent.normalName() == "li" } === copy
                    }
                    val checked = checkbox?.let { it.hasClass("checked") || it.hasAttr("checked") }
                    checkbox?.remove()
                    ArticleListItem(marker, checked, parseArticle(listItemBody(copy)))
                }
                if (items.isNotEmpty()) result.add(ArticleBlock.ListBlock(items))
            }
            "hr" -> result.add(ArticleBlock.Rule)
            "details" -> {
                val summary = element.children().firstOrNull { it.normalName() == "summary" }
                val body = element.clone().apply { children().firstOrNull { it.normalName() == "summary" }?.remove() }
                result.add(ArticleBlock.Details(summary?.html()?.takeIf { summary.text().isNotBlank() } ?: "详细内容",
                    parseArticle(body.html()), element.hasAttr("open")))
            }
            "img" -> {
                val raw = element.attr("src")
                if (element.hasClass("emoji")) result.add(ArticleBlock.TextBlock(element.outerHtml(), quote))
                else mediaUrl(raw)?.let { url ->
                    val lightbox = element.parents().firstOrNull { it.hasClass("lightbox") }
                    val metadata = lightbox?.selectFirst(".meta")
                    val information = metadata?.selectFirst(".informations")?.text().orEmpty()
                    val dimensions = Regex("\\d+\\s*[×x]\\s*\\d+").find(information)?.value.orEmpty()
                    val width = element.attr("width").toFloatOrNull()
                    val height = element.attr("height").toFloatOrNull()
                    val ratio = if (width != null && height != null && width > 0 && height > 0) {
                        (width / height).takeIf { it.isFinite() && it > 0 }
                    } else null
                    val original = lightbox?.attr("href")?.let(::mediaUrl) ?: url
                    result.add(ArticleBlock.Image(if (element.hasClass("animated")) original else previewSource(element, url), element.attr("alt"), ratio,
                        originalUrl = original,
                        filename = metadata?.selectFirst(".filename")?.text().orEmpty(),
                        dimensions = dimensions, sizeLabel = information.removePrefix(dimensions).trim(),
                        displayWidth = width?.takeIf { lightbox == null && ratio != null && it.isFinite() && it > 0 }))
                }
            }
            "iframe", "video", "audio" -> {
                val raw = element.attr("src").ifBlank { element.selectFirst("source")?.attr("src").orEmpty() }
                LinuxDoLink.webUrl(raw)?.let { result.add(ArticleBlock.Embed(it, "打开媒体网页")) }
            }
            "blockquote", "aside" -> {
                if (element.hasClass("onebox")) {
                    parseOnebox(element)?.let(result::add)
                } else if (element.normalName() == "blockquote") {
                    callout(element)?.let(result::add) ?: run {
                        val body = element.clone()
                        val content = parseArticle(body.html()).map { if (it is ArticleBlock.TextBlock) it.copy(quote = true) else it }
                        if (content.isNotEmpty()) result.add(ArticleBlock.Quote(content))
                    }
                } else {
                    val siteQuote = element.normalName() == "aside" && element.hasClass("quote")
                    val title = if (siteQuote) element.children().firstOrNull { it.hasClass("title") } else null
                    val author = if (siteQuote) element.attr("data-display-name").ifBlank {
                        title?.clone()?.apply { select("img, .quote-controls").remove() }?.text()?.trim()?.removeSuffix(":").orEmpty()
                    }.ifBlank { element.attr("data-username") } else ""
                    val topic = element.attr("data-topic").toIntOrNull()?.takeIf { it > 0 }
                    val post = element.attr("data-post").toIntOrNull()?.takeIf { it > 0 }
                    val source = if (siteQuote && topic != null) "https://linux.do/t/topic/$topic" + (post?.let { "/$it" } ?: "") else null
                    val body = element.clone().apply {
                        if (title != null) children().firstOrNull { it.hasClass("title") }?.remove()
                        // The direct blockquote is the site's body wrapper, not a second nested quote.
                        if (siteQuote) children().filter { it.normalName() == "blockquote" }.forEach { it.unwrap() }
                    }
                    val content = parseArticle(body.html()).map { if (it is ArticleBlock.TextBlock) it.copy(quote = true) else it }
                    if (content.isNotEmpty() || author.isNotBlank()) result.add(ArticleBlock.Quote(content, author,
                        title?.selectFirst("img.avatar")?.attr("src")?.let(::mediaUrl), source))
                }
            }
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "summary" -> {
                if (element.select(".spoiler img:not(.emoji)").isNotEmpty()) {
                    val pending = StringBuilder()
                    fun flush() {
                        if (pending.isNotEmpty()) {
                            result.addAll(parseArticle(pending.toString()).map { if (it is ArticleBlock.TextBlock) it.copy(quote = quote) else it })
                            pending.clear()
                        }
                    }
                    element.childNodes().flatMap(::spoilerFragments).forEach { fragment ->
                        if (fragment.region != null) { flush(); walk(fragment.region, quote) } else pending.append(fragment.html)
                    }
                    flush(); return
                }
                val images = element.select("img:not(.emoji)")
                val copy = element.clone().apply { select("img:not(.emoji), .lightbox .meta, .lightbox-wrapper .meta").remove() }
                val level = element.normalName().removePrefix("h").toIntOrNull()?.takeIf { it in 1..6 } ?: 0
                if (copy.text().isNotBlank() || copy.select("img.emoji").isNotEmpty()) result.add(
                    ArticleBlock.TextBlock(
                        if (element.normalName() == "li") "• ${copy.html()}" else copy.html(),
                        quote,
                        level > 0,
                        level,
                        copy.text().trim(),
                    )
                )
                images.forEach { walk(it, quote) }
            }
            else -> {
                if (element.children().isEmpty() && element.text().isNotBlank()) result.add(ArticleBlock.TextBlock(element.html(), quote))
                else element.childNodes().forEach { node ->
                    if (node is Element) walk(node, quote)
                    else if (node is TextNode && !node.isBlank) result.add(ArticleBlock.TextBlock(node.outerHtml(), quote))
                }
            }
        }
    }
    walk(Jsoup.parseBodyFragment(html).body())
    return result
}

private data class SpoilerFragment(val html: String = "", val region: Element? = null)

/** List items may omit p wrappers; keep adjacent inline nodes in one paragraph. */
private fun listItemBody(element: Element): String = buildString {
    val inline = StringBuilder()
    fun flush() {
        val html = inline.toString().trim()
        if (html.isNotEmpty()) append("<p>").append(html).append("</p>")
        inline.clear()
    }
    element.childNodes().forEach { node ->
        if (node is Element && node.tag().isBlock) { flush(); append(node.outerHtml()) }
        else inline.append(node.outerHtml())
    }
    flush()
}

private fun spoilerFragments(node: Node): List<SpoilerFragment> {
    if (node !is Element) return listOf(SpoilerFragment(node.outerHtml()))
    if (node.hasClass("spoiler") && node.select("img:not(.emoji)").isNotEmpty()) return listOf(SpoilerFragment(region = node))
    if (node.select(".spoiler img:not(.emoji)").isEmpty()) return listOf(SpoilerFragment(node.outerHtml()))
    return node.childNodes().flatMap(::spoilerFragments).map { fragment ->
        if (fragment.region != null) fragment else SpoilerFragment(node.clone().empty().append(fragment.html).outerHtml())
    }
}

private data class ArticleImageLocation(val image: ArticleBlock.Image, val path: String, val quotes: List<String>)

private fun articleImageLocations(blocks: List<ArticleBlock>, includeSpoilers: Boolean = false): List<ArticleImageLocation> = buildList {
    fun visit(items: List<ArticleBlock>, parent: String, quotes: List<String>) {
        items.forEachIndexed { index, block ->
            val path = "$parent-$index"
            when (block) {
                is ArticleBlock.Image -> add(ArticleImageLocation(block, path, quotes))
                is ArticleBlock.Quote -> visit(block.blocks, path, quotes + path)
                is ArticleBlock.Callout -> visit(block.blocks, path, quotes + path)
                is ArticleBlock.Details -> visit(block.blocks, path, quotes + path)
                is ArticleBlock.Spoiler -> if (includeSpoilers) visit(block.blocks, path, quotes)
                is ArticleBlock.ListBlock -> block.items.forEachIndexed { item, value -> visit(value.blocks, "$path-item$item", quotes) }
                else -> Unit
            }
        }
    }
    visit(blocks, "root", emptyList())
}

internal fun List<ArticleBlock>.articleImages(): List<ArticleBlock.Image> = articleImageLocations(this).map { it.image }

private fun closedDetails(blocks: List<ArticleBlock>, parent: String = "root"): List<String> = buildList {
    blocks.forEachIndexed { index, block ->
        val path = "$parent-$index"
        when (block) {
            is ArticleBlock.Details -> { if (!block.open) add(path); addAll(closedDetails(block.blocks, path)) }
            is ArticleBlock.Quote -> addAll(closedDetails(block.blocks, path))
            is ArticleBlock.Callout -> { if (!block.open) add(path); addAll(closedDetails(block.blocks, path)) }
            is ArticleBlock.Spoiler -> addAll(closedDetails(block.blocks, path))
            is ArticleBlock.ListBlock -> block.items.forEachIndexed { item, value -> addAll(closedDetails(value.blocks, "$path-item$item")) }
            else -> Unit
        }
    }
}

internal data class InlineEmoji(val url: String, val name: String)
internal data class InlineArticle(val text: AnnotatedString, val emojis: Map<String, InlineEmoji>)

internal fun parseInlineArticle(html: String, linkColor: Color, onFootnote: ((String) -> Unit)? = null): InlineArticle {
    val emojis = linkedMapOf<String, InlineEmoji>()
    var spoilerIndex = 0
    val text = buildAnnotatedString {
        var afterBreak = false
        fun appendNode(node: Node) {
            if (node is TextNode) {
                val value = node.text()
                append(if (afterBreak) value.trimStart() else value)
                if (value.isNotBlank()) afterBreak = false
            }
            else if (node is Element) {
                val children: AnnotatedString.Builder.() -> Unit = { node.childNodes().forEach(::appendNode) }
                val explicitColor = articleStyleColor(node.attr("style"))
                if (explicitColor != null) pushStyle(SpanStyle(color = explicitColor))
                if (node.hasClass("spoiler")) {
                    pushStringAnnotation("spoiler", (spoilerIndex++).toString())
                    children()
                    pop()
                } else when (node.normalName()) {
                    "br" -> { append("\n"); afterBreak = true }
                    "strong", "b" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold), children)
                    "em", "i" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic), children)
                    "del", "s" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), children)
                    "small" -> withStyle(SpanStyle(fontSize = .8.em), children)
                    "code" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = linkColor.copy(alpha = 0.1f)), children)
                    "sup" -> withStyle(SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript, fontSize = .75.em), children)
                    "sub" -> withStyle(SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Subscript, fontSize = .75.em), children)
                    "img" -> {
                        val url = mediaUrl(node.attr("src"))
                        if (node.hasClass("emoji") && url != null) {
                            val id = "emoji-${emojis.size}"
                            emojis[id] = InlineEmoji(url, node.attr("alt").ifBlank { node.attr("title") }.ifBlank { "表情" })
                            appendInlineContent(id, "\uFFFC")
                            afterBreak = false
                        } else append(node.attr("alt"))
                    }
                    "a" -> {
                        val footnoteId = footnoteReferenceId(node)
                        val url = ExternalAppLinkPolicy.contentUrl(node.attr("href"))
                        val color = explicitColor ?: node.parents().firstNotNullOfOrNull { articleStyleColor(it.attr("style")) } ?: linkColor
                        if (footnoteId != null) {
                            if (onFootnote != null) withLink(LinkAnnotation.Clickable("footnote-$footnoteId",
                                TextLinkStyles(SpanStyle(color = color))) { onFootnote(footnoteId) }, children)
                            else children()
                        } else if (url != null) withStyle(SpanStyle(color = color)) {
                            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))), children)
                        }
                        else children()
                    }
                    "script", "style" -> Unit
                    else -> children()
                }
                if (explicitColor != null) pop()
            }
        }
        Jsoup.parseBodyFragment(html).body().childNodes().forEach(::appendNode)
    }
    return InlineArticle(text, emojis)
}

@Composable
private fun rememberArticleInline(html: String, color: Color): InlineArticle {
    val onFootnote = LocalArticleFootnoteReference.current
    return remember(html, color, onFootnote) { parseInlineArticle(html, color, onFootnote) }
}

@Composable
internal fun SiteEmoji(name: String, modifier: Modifier = Modifier, source: String? = null) {
    val metadata = LocalAvatarMetadata.current
    val key = name.removeSurrounding(":")
    var url by remember(key, source, metadata) {
        mutableStateOf(source ?: if (metadata == null) AvatarMetadataCache.standardEmojiUrl(key) else null)
    }
    LaunchedEffect(key, source, metadata) {
        if (source == null) url = metadata?.emojiUrl(key) ?: AvatarMetadataCache.standardEmojiUrl(key)
    }
    val pixels = with(LocalDensity.current) { (24.dp.toPx() * fontScale).toInt().coerceAtLeast(1) }
    AvatarImage(url?.let(::mediaUrl), pixels, animate = true,
        cacheKey = url?.let { "emoji:$it" },
        modifier = modifier.size(24.dp).semantics { contentDescription = name }, contentScale = ContentScale.Fit) {
        Icon(Icons.Rounded.Mood, null, Modifier.fillMaxSize(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RichContent(html: String, onImage: (String) -> Unit, onLink: (String) -> Unit,
                galleryKey: String = "article", galleryBottomInset: Dp = 0.dp,
                poll: @Composable (String) -> Unit = { Text("投票，请打开原帖查看") }) {
    val blocks = remember(html) { parseArticle(html) }
    val footnotes = remember(html) { articleFootnotes(html) }
    val footnoteHost = LocalArticleFootnotes.current
    val footnoteOwner = remember(html) { Any() }
    val currentImage = rememberUpdatedState(onImage)
    val currentLink = rememberUpdatedState(onLink)
    val onFootnote = remember(footnotes, footnoteHost, footnoteOwner) { { id: String ->
        footnotes[id]?.let { note -> footnoteHost?.show?.invoke(footnoteOwner, note,
            { currentImage.value(it) }, { currentLink.value(it) }) }
        Unit
    } }
    DisposableEffect(footnoteHost, footnoteOwner) { onDispose { footnoteHost?.dismiss?.invoke(footnoteOwner) } }
    val linkHandler = remember(onLink) { object : UriHandler {
        override fun openUri(uri: String) = onLink(uri)
    } }
    val locations = remember(blocks) { articleImageLocations(blocks) }
    val imageIndices = remember(locations) { locations.mapIndexed { index, location -> location.path to index }.toMap() }
    val images = remember(locations) { locations.map { it.image } }
    val anchors = remember(blocks) { List(images.size) { GalleryThumbnailAnchor() } }
    // listSaver drops empty lists; an expanded article must also survive restoration.
    var collapsed by rememberSaveable(html, stateSaver = Saver<List<String>, ArrayList<String>>(
        save = { ArrayList(it) }, restore = { it.toList() })) {
        mutableStateOf(closedDetails(blocks))
    }
    val gallery = LocalImageGallery.current
    val origin = rememberSaveable { UUID.randomUUID().toString() }
    val bottomInset = with(LocalDensity.current) { galleryBottomInset.toPx() }
    RegisterGalleryOrigin(origin) { image, index ->
        anchors.getOrNull(index)?.takeIf { images.getOrNull(index)?.let { sameGalleryImage(it, image) } == true }?.also {
            val ancestors = locations[index].quotes
            if (ancestors.any { path -> path in collapsed }) {
                collapsed = collapsed.filterNot { path -> path in ancestors }
                withFrameNanos { }
                while (it.coordinates?.isAttached != true) withFrameNanos { }
            }
            val size = it.coordinates?.takeIf { layout -> layout.isAttached }?.size
            it.requester.bringIntoView(size?.let { bounds -> Rect(0f, 0f, bounds.width.toFloat(), bounds.height + bottomInset) })
            withFrameNanos { }
        }
    }
    CompositionLocalProvider(LocalArticleFootnoteReference provides onFootnote) {
    ArticleBlocks(blocks, "root", collapsed, { path ->
        collapsed = if (path in collapsed) collapsed - path else collapsed + path
    }, onLink, linkHandler, poll, onImage) { block, path ->
        val index = imageIndices.getValue(path)
        ArticleImage(block, anchors[index], "article-image-$galleryKey-$index") {
            if (gallery == null) onImage(block.originalUrl)
            else gallery.open(images, index, origin, anchors[index])
        }
    }
    }
}

@Composable
private fun ArticleBlocks(blocks: List<ArticleBlock>, parent: String, collapsed: List<String>, toggle: (String) -> Unit,
    onLink: (String) -> Unit, linkHandler: UriHandler, poll: @Composable (String) -> Unit, onImage: (String) -> Unit,
    firstLineIndent: TextUnit = TextUnit.Unspecified,
    image: @Composable (ArticleBlock.Image, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEachIndexed { index, block ->
            val path = "$parent-$index"
            key(path) { when (block) {
            is ArticleBlock.Details -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).testTag("article-details-$path")) {
                val hidden = path in collapsed
                val color = MaterialTheme.colorScheme.primary
                val summary = rememberArticleInline(block.summary, color).text
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { toggle(path) }
                    .semantics { contentDescription = if (hidden) "展开折叠内容" else "收起折叠内容" }
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (hidden) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                if (!hidden) Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    ArticleBlocks(block.blocks, path, collapsed, toggle, onLink, linkHandler, poll, onImage, image = image)
                }
            }
            is ArticleBlock.Callout -> {
                val hidden = path in collapsed
                val accent = calloutAccent(block.type)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.12f)).testTag("article-callout-$path")) {
                    Row(Modifier.fillMaxWidth().then(if (block.foldable) Modifier.clickable(role = Role.Button) { toggle(path) }
                        .semantics { contentDescription = if (hidden) "展开提示" else "收起提示" } else Modifier)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(calloutIcon(block.type), null, Modifier.size(18.dp), tint = accent)
                        Text(block.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = accent)
                        if (block.foldable) Icon(if (hidden) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null,
                            Modifier.size(18.dp), tint = accent)
                    }
                    if (!hidden) Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        ArticleBlocks(block.blocks, path, collapsed, toggle, onLink, linkHandler, poll, onImage, image = image)
                    }
                }
            }
            is ArticleBlock.Quote -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp).testTag("article-quote-$path"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (block.author.isNotBlank() || block.source != null) {
                    val hidden = path in collapsed
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 36.dp).clickable(role = Role.Button) { toggle(path) }
                            .semantics { contentDescription = if (hidden) "展开引用" else "收起引用" },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            block.avatar?.let { url -> AsyncImage(url, "引用作者头像", Modifier.size(20.dp)
                                .clip(RoundedCornerShape(50)).testTag("quote-avatar-$path"), contentScale = ContentScale.Crop) }
                            Text(block.author.ifBlank { "引用" }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Icon(if (hidden) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null,
                                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        block.source?.let { source -> Box(Modifier.size(36.dp).clickable(role = Role.Button) { onLink(source) }
                            .semantics { contentDescription = "跳转引用原帖" }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.NorthEast, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    }
                }
                if (path !in collapsed) {
                    Box(Modifier.fillMaxWidth()) {
                        if (block.source == null) {
                            Text("“", Modifier.align(Alignment.TopStart),
                                style = MaterialTheme.typography.displayMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f))
                        }
                        ArticleBlocks(block.blocks, path, collapsed, toggle, onLink, linkHandler, poll, onImage,
                            firstLineIndent = if (block.source == null) 48.sp else TextUnit.Unspecified,
                            image = image)
                    }
                }
            }
            is ArticleBlock.TextBlock -> {
                val color = MaterialTheme.colorScheme.primary
                val article = rememberArticleInline(block.html, color)
                val headingCollector = LocalArticleHeadingCollector.current
                val headingRequester = if (headingCollector != null && block.heading && block.headingLevel in 1..6) {
                    remember { BringIntoViewRequester() }
                } else null
                if (headingRequester != null) {
                    DisposableEffect(path, block.headingText, headingRequester) {
                        headingCollector?.invoke(ArticleHeading(block.headingLevel, block.headingText, path), headingRequester)
                        onDispose { }
                    }
                }
                CompositionLocalProvider(LocalUriHandler provides linkHandler) {
                    SpoilerArticleText(article, block.html, "article-text-$path",
                        if (block.heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        if (block.quote) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        textIndent = if (index == 0 && firstLineIndent != TextUnit.Unspecified) TextIndent(firstLine = firstLineIndent) else TextIndent.None,
                        modifier = headingRequester?.let { Modifier.bringIntoViewRequester(it) } ?: Modifier)
                }
            }
            is ArticleBlock.Spoiler -> {
                val hasImages = remember(block) { articleImageLocations(block.blocks, includeSpoilers = true).isNotEmpty() }
                SpoilerRegion(block, "article-spoiler-$path", hideOnContentClick = !hasImages) { revealed, toggleSpoiler ->
                    val spoilerLinkHandler = if (hasImages && revealed) linkHandler else
                        object : UriHandler { override fun openUri(uri: String) = toggleSpoiler() }
                    ArticleBlocks(block.blocks, path, collapsed, toggle,
                        if (hasImages && revealed) onLink else ({ toggleSpoiler() }), spoilerLinkHandler, poll, onImage) { secret, secretPath ->
                        SpoilerArticleImage(secret, "spoiler-image-$secretPath", revealed, toggleSpoiler, onImage)
                    }
                }
            }
            is ArticleBlock.Code -> CodeBlock(block.text)
            is ArticleBlock.Table -> ArticleTable(block, path, linkHandler)
            is ArticleBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.items.forEachIndexed { itemIndex, item ->
                    val itemPath = "$path-item$itemIndex"
                    Row(Modifier.fillMaxWidth().testTag("article-list-item-$itemPath")
                        .semantics { item.checked?.let { stateDescription = if (it) "已完成" else "未完成" } },
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.checked != null) Icon(
                            if (item.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            null, Modifier.padding(top = 3.dp).size(20.dp),
                            tint = if (item.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text(item.marker, Modifier.widthIn(min = 18.dp), style = MaterialTheme.typography.bodyLarge)
                        Box(Modifier.weight(1f)) {
                        ArticleBlocks(item.blocks, itemPath, collapsed, toggle, onLink, linkHandler, poll, onImage, image = image)
                        }
                    }
                }
            }
            is ArticleBlock.Image -> image(block, path)
            is ArticleBlock.Embed -> OneboxPreview(block, onLink)
            is ArticleBlock.Poll -> poll(block.name)
            ArticleBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        } } }
    }
}

@Composable
private fun ArticleTable(block: ArticleBlock.Table, path: String, linkHandler: UriHandler) {
    val color = MaterialTheme.colorScheme.primary
    val columns = block.rows.maxOfOrNull { it.cells.size } ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).testTag("article-table-$path")) {
        block.rows.forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth().then(
                if (row.header) Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .04f)) else Modifier),
                verticalAlignment = Alignment.CenterVertically) {
                repeat(columns) { column ->
                    val html = row.cells.getOrNull(column).orEmpty()
                    val article = rememberArticleInline(html, color)
                    CompositionLocalProvider(LocalUriHandler provides linkHandler) {
                        Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                            SpoilerArticleText(article, "table-$path-$rowIndex-$column", "article-table-cell-$path-$rowIndex-$column",
                                if (row.header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                                MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if (rowIndex < block.rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
            }
        }
    }
}

private fun calloutIcon(type: ArticleCalloutType): ImageVector = when (type) {
    ArticleCalloutType.TIP -> Icons.Rounded.Lightbulb
    ArticleCalloutType.TODO, ArticleCalloutType.SUCCESS -> Icons.Rounded.CheckCircle
    ArticleCalloutType.WARNING, ArticleCalloutType.CAUTION -> Icons.Rounded.Warning
    ArticleCalloutType.FAILURE, ArticleCalloutType.DANGER -> Icons.Rounded.Error
    ArticleCalloutType.BUG -> Icons.Rounded.BugReport
    ArticleCalloutType.QUESTION -> Icons.Rounded.Help
    ArticleCalloutType.EXAMPLE, ArticleCalloutType.QUOTE -> Icons.Rounded.FormatQuote
    else -> Icons.Rounded.Info
}

@Composable
private fun calloutAccent(type: ArticleCalloutType): Color = when (type) {
    ArticleCalloutType.WARNING, ArticleCalloutType.CAUTION -> Color(0xFFE0A000)
    ArticleCalloutType.FAILURE, ArticleCalloutType.DANGER, ArticleCalloutType.BUG -> MaterialTheme.colorScheme.error
    ArticleCalloutType.TIP, ArticleCalloutType.SUCCESS -> Color(0xFF00A896)
    ArticleCalloutType.QUESTION -> Color(0xFFE0A000)
    ArticleCalloutType.EXAMPLE -> Color(0xFF9B70DA)
    ArticleCalloutType.ABSTRACT -> Color(0xFF00A9BB)
    ArticleCalloutType.QUOTE -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> Color(0xFF448AFF)
}

@Composable
private fun CodeBlock(code: String) {
    val context = LocalContext.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.align(Alignment.End).clickable(role = Role.Button) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("代码", code))
            copied = true
        }.semantics { contentDescription = "复制代码" }.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer {
            Text(code, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 13.dp, end = 13.dp, bottom = 13.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SpoilerArticleImage(image: ArticleBlock.Image, tag: String, revealed: Boolean, toggle: () -> Unit, onImage: (String) -> Unit) {
    val gallery = LocalImageGallery.current
    val anchor = remember(image) { GalleryThumbnailAnchor() }
    val origin = rememberSaveable(image) { UUID.randomUUID().toString() }
    RegisterGalleryOrigin(origin) { current, _ ->
        if (!sameGalleryImage(current, image)) null else {
            anchor.requester.bringIntoView()
            withFrameNanos { }
            anchor
        }
    }
    ArticleImage(image, anchor, tag, hideSpoiler = if (revealed) toggle else null) {
        if (!revealed) toggle()
        else if (gallery == null) onImage(image.originalUrl)
        else gallery.open(listOf(image), 0, origin, anchor)
    }
}

@Composable
private fun ArticleImage(image: ArticleBlock.Image, anchor: GalleryThumbnailAnchor, tag: String,
    hideSpoiler: (() -> Unit)? = null, open: () -> Unit) {
    val gallery = LocalImageGallery.current
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var animation by remember(image.url) { mutableStateOf<Animatable?>(null) }
    var loading by remember(image.url) { mutableStateOf(true) }
    var failed by remember(image.url) { mutableStateOf(false) }
    var retry by remember(image.url) { mutableIntStateOf(0) }
    LifecycleResumeEffect(animation) {
        val drawable = animation
        if (drawable?.isRunning == false) drawable.start()
        onPauseOrDispose { drawable?.stop() }
    }
    var decodedRatio by remember(image.url) { mutableStateOf<Float?>(null) }
    val cornerRadius = with(LocalDensity.current) { 11.dp.toPx() }
    SideEffect { anchor.cornerRadius = cornerRadius }
    DisposableEffect(anchor) { onDispose { anchor.coordinates = null; anchor.painter = null } }
    Box(
        Modifier.then(image.displayWidth?.let { Modifier.widthIn(max = it.dp) } ?: Modifier)
            .heightIn(max = 300.dp).aspectRatio(decodedRatio ?: image.ratio ?: 1.6f)
            .bringIntoViewRequester(anchor.requester).onGloballyPositioned { anchor.coordinates = it }.testTag(tag)
            .graphicsLayer { alpha = if (gallery?.hiddenAnchor === anchor) 0f else 1f }
            .clip(RoundedCornerShape(11.dp))
            .then(if (loading || failed) Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) else Modifier)
            .clickable(onClick = { if (failed) { loading = true; failed = false; retry++ } else open() })) {
        key(retry) { AsyncImage(image.url, image.alt.ifBlank { "帖子图片，点击放大" }, Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onLoading = { loading = true; failed = false },
            onError = { loading = false; failed = true },
            onSuccess = { state ->
                loading = false
                failed = false
                val bitmap = state.result.image
                if (bitmap.width > 0 && bitmap.height > 0) decodedRatio = bitmap.width.toFloat() / bitmap.height
                anchor.painter = state.painter
                animation = bitmap.asDrawable(resources) as? Animatable
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) animation?.stop()
            }) }
        if (loading) CommunityLoadingIndicator(Modifier.align(Alignment.Center).size(24.dp).testTag("$tag-loading"))
        if (failed) Text("加载失败，点击重试", Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ImageAspectBadge(decodedRatio ?: image.ratio)
        hideSpoiler?.let { hide ->
            Box(Modifier.align(Alignment.TopEnd).size(48.dp).clip(RoundedCornerShape(50))
                .clickable(role = Role.Button, onClick = hide)
                .semantics { contentDescription = "隐藏剧透图片" }.testTag("$tag-hide-spoiler"),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(32.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = .6f)).padding(6.dp), tint = Color.White)
            }
        }
    }
}
