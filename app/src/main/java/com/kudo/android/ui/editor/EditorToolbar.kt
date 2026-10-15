package com.kudo.android.ui.editor

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.FormatTextdirectionLToR
import androidx.compose.material.icons.automirrored.rounded.FormatTextdirectionRToL
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kudo.android.ui.components.*
import kotlinx.coroutines.delay

@Composable
internal fun EditorToolbar(value: TextFieldValue, change: (TextFieldValue) -> Unit, pickImage: () -> Unit,
    enabled: Boolean, uploading: Boolean, focus: () -> Unit, rtl: Boolean, direction: () -> Unit,
    modifier: Modifier = Modifier, preview: () -> Unit = {}) {
    var menu by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val toolbarScroll = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current
    fun open(name: String) { keyboard?.hide(); menu = name }
    fun apply(transform: (TextFieldValue) -> TextFieldValue) { if (enabled) { change(transform(value)); focus() } }
    @Composable fun tool(icon: ImageVector, label: String, action: () -> Unit) {
        CommunityIconButton(icon, label, action, enabled = enabled)
    }
    LaunchedEffect(toolbarScroll.maxValue) {
        val preferences = context.getSharedPreferences("kudo_preferences", 0)
        if (toolbarScroll.maxValue > 0 && !preferences.getBoolean("editor_toolbar_scroll_guide_shown", false)) {
            preferences.edit().putBoolean("editor_toolbar_scroll_guide_shown", true).apply()
            delay(350)
            toolbarScroll.animateScrollTo(toolbarScroll.maxValue)
            delay(250)
            toolbarScroll.animateScrollTo(0)
        }
    }
    Row(modifier.horizontalScroll(toolbarScroll).testTag("editor-toolbar"), verticalAlignment = Alignment.CenterVertically) {
        CommunityIconButton(Icons.Rounded.Visibility, "预览正文", preview, enabled = enabled && value.text.isNotBlank())
        tool(Icons.Rounded.FormatBold, "粗体") { apply { it.wrapMarkdown("**") } }
        tool(Icons.Rounded.FormatItalic, "斜体") { apply { it.wrapMarkdown("*") } }
        tool(Icons.Rounded.TextFields, "文本大小") { open("size") }
        tool(Icons.Rounded.Link, "插入链接") { open("link") }
        tool(Icons.Rounded.FormatQuote, "插入引用") { apply { it.lineMarkdown(MarkdownLines.QUOTE) } }
        CommunityIconButton(Icons.Rounded.AddPhotoAlternate, "上传图片", pickImage, enabled = enabled && !uploading)
        tool(Icons.AutoMirrored.Rounded.FormatListBulleted, "列表") { open("list") }
        tool(if (rtl) Icons.AutoMirrored.Rounded.FormatTextdirectionLToR else Icons.AutoMirrored.Rounded.FormatTextdirectionRToL, "切换文字方向", direction)
        tool(Icons.Rounded.EmojiEmotions, "表情") { open("emoji") }
        tool(Icons.Rounded.Code, "插入代码块") { apply { it.blockMarkdown("```\n", "\n```") } }
        tool(Icons.Rounded.Add, "更多格式") { open("more") }
    }
    menu?.let { panel ->
        EditorToolSheet(when (panel) { "size" -> "文本大小"; "list" -> "列表"; "link" -> "插入链接"; "emoji" -> "表情"; else -> "更多格式" }, { menu = null }) { close ->
            fun choose(transform: (TextFieldValue) -> TextFieldValue) { apply(transform); close() }
            when (panel) {
                "size" -> {
                    listOf("标题 1" to MarkdownLines.H1, "标题 2" to MarkdownLines.H2, "标题 3" to MarkdownLines.H3,
                        "标题 4" to MarkdownLines.H4, "段落" to MarkdownLines.PARAGRAPH).forEach { (label, command) ->
                        EditorToolOption(label) { choose { it.lineMarkdown(command) } }
                    }
                    EditorToolOption("小字") { choose { it.wrapMarkdown("<small>", "</small>") } }
                }
                "list" -> listOf("项目符号列表" to MarkdownLines.BULLET, "编号列表" to MarkdownLines.NUMBERED,
                    "任务列表" to MarkdownLines.TASK).forEach { (label, command) -> EditorToolOption(label) { choose { it.lineMarkdown(command) } } }
                "link" -> EditorLinkForm(value.text.substring(value.selection.min, value.selection.max)) { label, url -> choose { it.linkMarkdown(label, url) } }
                "emoji" -> EditorEmojis { emoji -> choose { it.replaceMarkdown(emoji) } }
                else -> {
                    EditorToolOption("行内代码") { choose { it.wrapMarkdown("`", placeholder = "代码") } }
                    EditorToolOption("删除线") { choose { it.wrapMarkdown("~~") } }
                    EditorToolOption("插入表格") { choose { it.insertTemplate("| 标题 | 标题 |\n| --- | --- |\n| 内容 | 内容 |") } }
                    EditorToolOption("分割线") { choose { it.insertTemplate("---") } }
                    EditorToolOption("折叠内容") { choose { it.blockMarkdown("[details=\"摘要\"]\n", "\n[/details]") } }
                    EditorToolOption("模糊剧透") { choose { it.wrapMarkdown("[spoiler]", "[/spoiler]") } }
                    EditorToolOption("插入目录") { choose { it.insertTemplate("[TOC]") } }
                    EditorToolOption("添加脚注") { choose { it.wrapMarkdown("^[", "]", "脚注内容") } }
                    EditorToolOption("插入日期／时间") { choose { it.replaceMarkdown("[date=${java.time.LocalDate.now()} time=${java.time.LocalTime.now().withNano(0)} timezone=\"${java.time.ZoneId.systemDefault().id}\"]") } }
                    EditorToolOption("插入公式") { choose { it.blockMarkdown("$$\n", "\n$$", "E = mc^2") } }
                    EditorToolOption("Mermaid 图表") { choose { it.blockMarkdown("```mermaid\n", "\n```", "graph TD\n    A[开始] --> B[结束]") } }
                    EditorToolOption("Graphviz 图表") { choose { it.blockMarkdown("[graphviz]\n", "\n[/graphviz]", "digraph {\n    A -> B\n}") } }
                }
            }
        }
    }
}

internal fun TextFieldValue.insertTemplate(template: String): TextFieldValue =
    copy(selection = androidx.compose.ui.text.TextRange(selection.max)).blockMarkdown("", placeholder = template)

/** A full-window host keeps tool sheets above the compact reply sheet and its IME. */
@Composable
internal fun EditorToolSheet(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) dismiss()
    }
    Dialog({ visibility.targetState = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            CommunitySheet(visibility, title, { visibility.targetState = false }, showTitle = true) { content { visibility.targetState = false } }
        }
    }
}

@Composable
internal fun EditorToolOption(label: String, action: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EditorLinkForm(selected: String, insert: (String, String) -> Unit) {
    var label by remember { mutableStateOf(selected) }
    var url by remember { mutableStateOf("") }
    val normalized = url.trim().let { if (it.isNotEmpty() && !it.contains(":") && !it.startsWith("/") && !it.startsWith("#")) "https://$it" else it }
    val valid = normalized.startsWith("https://") || normalized.startsWith("http://") || normalized.startsWith("mailto:") || normalized.startsWith("/") || normalized.startsWith("#")
    Text("链接文字", style = MaterialTheme.typography.labelMedium)
    CommunityTextField(label, { label = it }, Modifier.fillMaxWidth().testTag("editor-link-label"), placeholder = "显示文字（选填）")
    HorizontalDivider()
    Text("链接地址", style = MaterialTheme.typography.labelMedium)
    CommunityTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("editor-link-url"), placeholder = "https://", singleLine = true)
    CommunityButton("插入", { insert(label.ifBlank { normalized }, normalized) }, Modifier.fillMaxWidth(), enabled = valid)
}
