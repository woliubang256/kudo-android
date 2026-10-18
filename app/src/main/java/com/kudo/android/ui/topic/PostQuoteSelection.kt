package com.kudo.android.ui.topic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import com.kudo.android.R
import com.kudo.android.data.Post
import com.kudo.android.data.postQuoteMarkdown
import com.kudo.android.ui.components.showKudoToast

internal val LocalPostQuoteSelection = staticCompositionLocalOf<Modifier> { Modifier }

@Composable
internal fun PostQuoteSelection(topicId: Int?, post: Post, reply: ((String) -> Unit)? = null,
    content: @Composable () -> Unit) {
    if (topicId == null || topicId <= 0) { content(); return }
    val context = LocalContext.current
    val parentClipboard = LocalClipboard.current
    val clipboard = remember(parentClipboard, topicId, post.id) { SelectionQuoteClipboard(parentClipboard) }
    val menuModifier = remember(clipboard, reply) {
        Modifier.appendTextContextMenuComponents {
            clipboard.copyItem = null
            separator()
            if (reply != null) item(QuoteSelectionKey, "引用", R.drawable.ic_text_quote) {
                val selected = clipboard.selectedText(this)
                close()
                if (selected != null) reply(postQuoteMarkdown(topicId, post, selected))
            }
            item(CopyQuoteSelectionKey, "复制引用", R.drawable.ic_text_copy) {
                val selected = clipboard.selectedText(this)
                close()
                if (selected != null) {
                    val markdown = postQuoteMarkdown(topicId, post, selected)
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    manager.setPrimaryClip(ClipData.newPlainText("引用", markdown))
                    showKudoToast(context, "已复制引用")
                }
            }
        }.filterTextContextMenuComponents { component ->
            if (component.key === TextContextMenuKeys.CopyKey && component is TextContextMenuItem) clipboard.copyItem = component
            component.key === TextContextMenuKeys.CopyKey ||
                component.key === TextContextMenuKeys.SelectAllKey ||
                component.key === QuoteSelectionKey || component.key === CopyQuoteSelectionKey
        }
    }
    CompositionLocalProvider(LocalClipboard provides clipboard, LocalPostQuoteSelection provides menuModifier) {
        content()
    }
}

private data object QuoteSelectionKey
private data object CopyQuoteSelectionKey

private class SelectionQuoteClipboard(private val parent: Clipboard) : Clipboard by parent {
    var copyItem: TextContextMenuItem? = null
    private var capture: ((String) -> Unit)? = null

    fun selectedText(session: TextContextMenuSession): String? {
        val copy = copyItem ?: return null
        var selected: String? = null
        capture = { selected = it }
        try { copy.onClick(session) } finally { capture = null }
        return selected?.takeIf(String::isNotBlank)
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val receiver = capture
        if (receiver == null) parent.setClipEntry(clipEntry)
        else clipEntry?.clipData?.getItemAt(0)?.text?.toString()?.let(receiver)
    }
}
