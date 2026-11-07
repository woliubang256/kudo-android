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

    @Test fun separateOccurrencesOfTheSameUrlKeepTheirOwnSourcesIncludingUnknown() {
        val first = ImageSource(42, 8, "第一处")
        val second = ImageSource(84, 3, "第二处")
        val state = ImageGalleryState()
        state.open(listOf(image, image, image), 1, "library", source = first, sources = listOf(first, second, null))
        assertEquals(second, state.session!!.sourceAt(1))
        assertEquals(first, state.session!!.sourceAt(0))
        assertNull(state.session!!.sourceAt(2))
        val saved = with(ImageGalleryState.Saver) { object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }.save(state) }
        val restored = ImageGalleryState.Saver.restore(saved!!)!!.session!!
        assertEquals(1, restored.index)
        assertEquals(listOf(first, second, null), restored.sources)
        assertNull(restored.sourceAt(2))
    }

    @Test fun openingTakesASnapshotOfImagesAndTheirSources() {
        val images = mutableListOf(image, image.copy(alt = "第二张"))
        val sources = mutableListOf<ImageSource?>(ImageSource(42, 8), null)
        val state = ImageGalleryState()
        state.open(images, 1, "library", sources = sources)
        images.clear()
        sources.clear()
        assertEquals(2, state.session!!.images.size)
        assertEquals("第二张", state.session!!.current.alt)
        assertEquals(ImageSource(42, 8), state.session!!.sourceAt(0))
        assertNull(state.session!!.sourceAt(1))
    }
}
