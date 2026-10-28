package com.kudo.android

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.kudo.android.data.SavedImage
import com.kudo.android.ui.editor.*
import org.junit.Assert.*
import org.junit.Test

class LibraryImageInsertionTest {
    private val image = SavedImage(originalUrl = "https://cdn.example.com/a.gif", thumbnailUrl = "https://cdn.example.com/thumb.jpg", alt = "表情")

    @Test fun usesOriginalLinkWithoutUploadAndDeduplicatesAcrossFoldersInSelectionOrder() {
        val other = image.copy(originalUrl = "https://cdn.example.com/b.png", alt = "另一张")
        val result = TextFieldValue("").insertLibraryImages(listOf(other, image, image.copy(folderId = "other")))
        assertEquals("![另一张](https://cdn.example.com/b.png)\n\n![表情](https://cdn.example.com/a.gif)\n", result.text)
        assertFalse(result.text.contains("thumb"))
        assertFalse(result.text.contains("upload://"))
        assertEquals(result.text.length - 1, result.selection.start)
    }

    @Test fun insertsAfterForwardAndReverseSelectionsWithoutReplacingText() {
        for (selection in listOf(TextRange(1, 3), TextRange(3, 1))) {
            val result = TextFieldValue("前原文后", selection, composition = TextRange(0, 1)).insertLibraryImages(listOf(image))
            assertEquals("前原文\n\n![表情](https://cdn.example.com/a.gif)\n\n后", result.text)
            assertNull(result.composition)
            assertTrue(result.selection.collapsed)
        }
    }

    @Test fun escapesMarkdownSyntaxAndPreservesExistingUrlEscapes() {
        val escaped = image.copy(alt = "猫 [开心]\\\n另行", originalUrl = "https://cdn.example.com/a (1).png?q=%20&x=<a>")
        assertEquals("![猫 \\[开心\\]\\\\ 另行](https://cdn.example.com/a%20%281%29.png?q=%20&x=%3Ca%3E)", savedImageMarkdown(escaped))
    }

    @Test fun emptyAndUnsafeSelectionsLeaveTheDraftUntouched() {
        val value = TextFieldValue("草稿", TextRange(1))
        assertEquals(value, value.insertLibraryImages(emptyList()))
        assertEquals(value, value.insertLibraryImages(listOf(image.copy(originalUrl = "file:///private/a.png"))))
    }
}
