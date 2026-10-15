package com.kudo.android.ui.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.LocalAvatarMetadata
import com.kudo.android.ui.topic.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EditorPreviewDialog(raw: String, dismiss: () -> Unit) {
    val metadata = LocalAvatarMetadata.current
    var preview by remember(raw) { mutableStateOf<MarkdownPreview?>(null) }
    var failure by remember(raw) { mutableStateOf(false) }
    var link by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(raw, metadata) {
        try { preview = withContext(Dispatchers.Default) { MarkdownPreviewRenderer.render(raw) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { failure = true }
        val emojis = metadata?.emojiDirectory().orEmpty().flatMap { it.items }.associate { it.name to it.url }
        if (emojis.isNotEmpty()) {
            try { preview = withContext(Dispatchers.Default) { MarkdownPreviewRenderer.render(raw, emojis) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Keep the first preview when optional emoji enrichment fails. */ }
        }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalArticleHeadingCollector provides null, LocalArticleFootnotes provides null, LocalReadingBlocked provides true) {
            ImageGalleryHost({ emptyList() }, { link = it }) {
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                    val panelHeight = maxHeight * .85f
                    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(min = minOf(240.dp, panelHeight), max = panelHeight)
                        .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surface).testTag("markdown-preview")) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("预览", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            CommunityIconButton(Icons.Rounded.Close, "关闭预览", dismiss)
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp).testTag("preview-content"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            val rendered = preview
                            when {
                                failure -> { Text("暂时无法生成预览，原文已保留。"); SelectionContainer { Text(raw) } }
                                rendered == null -> Text("正在生成预览…")
                                else -> {
                                    rendered.notices.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    RichContent(rendered.html, {}, { link = it }, galleryKey = "editor-preview")
                                }
                            }
                        }
                        link?.let { address -> SelectionContainer { Text("链接地址：$address", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) } }
                        Text("本地预览 · 最终效果以站点发布为准", Modifier.padding(horizontal = 20.dp, vertical = 14.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
