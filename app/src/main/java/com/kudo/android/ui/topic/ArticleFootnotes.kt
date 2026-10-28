package com.kudo.android.ui.topic

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.kudo.android.ui.components.liquid.LiquidModal
import com.kudo.android.ui.components.liquid.LiquidModalAction
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.kudo.android.data.ImageSource

internal data class ArticleFootnote(val id: String, val label: String, val html: String)

internal fun articleFootnotes(html: String): Map<String, ArticleFootnote> = buildMap {
    Jsoup.parseBodyFragment(html).select(".footnotes-list > li[id], .footnotes ol > li[id]").forEachIndexed { index, item ->
        val id = item.id()
        val body = item.clone().apply { select(".footnote-backref").remove() }
        if (id.isNotBlank() && !containsKey(id)) put(id, ArticleFootnote(id, (index + 1).toString(), body.html()))
    }
}

internal fun footnoteReferenceId(element: Element): String? = element.attr("href")
    .takeIf { it.startsWith("#") && it.length > 1 && (element.hasClass("footnote-ref") || element.parents().any { parent -> parent.hasClass("footnote-ref") }) }
    ?.removePrefix("#")

private data class FootnoteRequest(val owner: Any, val note: ArticleFootnote,
    val onImage: (String) -> Unit, val onLink: (String) -> Unit,
    val source: ImageSource?, val locals: CompositionLocalContext)

internal class ArticleFootnoteController(val show: (Any, ArticleFootnote, (String) -> Unit, (String) -> Unit, ImageSource?, CompositionLocalContext) -> Unit,
    val dismiss: (Any) -> Unit)

internal val LocalArticleFootnotes = staticCompositionLocalOf<ArticleFootnoteController?> { null }
internal val LocalArticleFootnoteReference = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** A root host keeps a short post's dialog independent of LazyColumn item constraints. */
@Composable
internal fun ArticleFootnoteHost(content: @Composable () -> Unit) {
    val visibility = remember { MutableTransitionState(false) }
    var request by remember { mutableStateOf<FootnoteRequest?>(null) }
    val controller = remember {
        ArticleFootnoteController({ owner, note, onImage, onLink, source, locals ->
            request = FootnoteRequest(owner, note, onImage, onLink, source, locals)
            visibility.targetState = true
        }, { owner -> if (request?.owner === owner) visibility.targetState = false })
    }
    LaunchedEffect(visibility.isIdle, visibility.currentState, visibility.targetState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) request = null
    }
    val showing = visibility.currentState || visibility.targetState
    val backdrop = rememberLayerBackdrop()
    val glass = LocalDisplaySettings.current.glass
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
            .then(if (showing) Modifier.clearAndSetSemantics {} else Modifier)
            .focusProperties { onEnter = { if (showing) cancelFocusChange() } }.focusGroup()) {
            CompositionLocalProvider(LocalArticleFootnotes provides controller,
                LocalReadingBlocked provides (LocalReadingBlocked.current || showing), content = content)
        }
        val selected = request
        if (selected != null) LiquidModal(visibility, "脚注 ${selected.note.label}", false, backdrop, glass,
            dismiss = { visibility.targetState = false }, maxDialogHeightFraction = .7f,
            actions = { LiquidModalAction("关闭", { visibility.targetState = false }, Modifier.fillMaxWidth()) }) {
            CompositionLocalProvider(selected.locals) {
            CompositionLocalProvider(LocalReadingBlocked provides true, LocalArticleHeadingCollector provides null) {
                RichContent(selected.note.html, selected.onImage, { url ->
                    visibility.targetState = false
                    selected.onLink(url)
                }, galleryKey = "footnote-${selected.note.id}", imageSource = selected.source)
            }
            }
        }
    }
}
