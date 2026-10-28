package com.kudo.android.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kudo.android.data.SavedImage
import com.kudo.android.data.validLibraryImageUrl

internal fun savedImageMarkdown(image: SavedImage): String {
    require(validLibraryImageUrl(image.originalUrl))
    val label = image.alt.ifBlank { "图片" }.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        .replace('\n', ' ').replace('\r', ' ')
    val url = image.originalUrl.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
        .replace("<", "%3C").replace(">", "%3E").replace("\\", "%5C")
    return "![$label]($url)"
}

internal fun TextFieldValue.insertLibraryImages(images: List<SavedImage>): TextFieldValue {
    val markdown = images.filter { validLibraryImageUrl(it.originalUrl) }.distinctBy { it.originalUrl }.joinToString("\n\n", transform = ::savedImageMarkdown)
    if (markdown.isEmpty()) return this
    val inserted = copy(selection = TextRange(selection.max)).blockMarkdown("", placeholder = markdown)
    return inserted.copy(selection = TextRange(inserted.selection.max), composition = null)
}
