package com.kudo.android

import androidx.compose.runtime.saveable.SaverScope
import com.kudo.android.data.ImageSource
import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Test

class ImageLibrarySourceTest {
    private val image = ArticleBlock.Image("https://cdn.example.com/a.png", "图片")

    @Test fun fullScreenKeepsExplicitReplySourceAcrossRestoration() {
        val source = ImageSource(42, 8, "回复的来源")
        val state = ImageGalleryState()
        state.open(listOf(image), 0, "temporary-origin", source = source)
        val saved = with(ImageGalleryState.Saver) { object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }.save(state) }
        val restored = ImageGalleryState.Saver.restore(saved!!)!!
        assertEquals(source, restored.session?.source)
        assertEquals("https://linux.do/t/topic/42/8", restored.session?.source?.url)
        assertNull(restored.session?.topicId)
    }

    @Test fun feedAlbumFallsBackToTheOriginalPostButUnknownOriginsHaveNoSource() {
        val feed = ImageGalleryState()
        feed.open(listOf(image), 0, "feed", topicId = 42)
        assertEquals(ImageSource(42), feed.session?.source)
        val unknown = ImageGalleryState()
        unknown.open(listOf(image), 0, "preview")
        assertNull(unknown.session?.source)
    }

    @Test fun legacyGalleryStateStillRestoresWithoutInventingAReply() {
        val legacy = listOf("origin", 0, 0, arrayListOf(arrayListOf<Any>(image.url, image.alt, 0f, image.originalUrl, "", "", "", 0f)), true)
        val restored = ImageGalleryState.Saver.restore(legacy)!!
        assertEquals(image, restored.session?.current)
        assertNull(restored.session?.source)
    }
}
