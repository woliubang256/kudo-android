package com.kudo.android

import com.kudo.android.ui.topic.ArticleBlock
import com.kudo.android.ui.topic.parseArticle
import org.junit.Assert.*
import org.junit.Test

class PictureArticleTest {
    private val target = "https://hb.lafi.sh/packet?from=post&v=1#detail"
    private fun picture(sources: String, image: String = "") =
        """<picture>$sources<img $image width="300" height="409" alt="红包卡片"></picture>"""

    @Test fun sourceOnlyRedpackRetainsTheImageItsLinkAndSurroundingParagraphs() {
        val blocks = parseArticle("""<p>通关越快奖励越多</p><p><a href="${target.replace("&", "&amp;")}">${picture(
            """<source type="image/svg+xml" srcset="/card.svg?tz=Asia%2FShanghai&amp;v=1 1x">""")}</a></p><p>游玩小程序</p>""")
        assertEquals("必须保留首段、红包图片和后续正文", 3, blocks.size)
        val image = blocks[1] as ArticleBlock.Image
        assertEquals("https://linux.do/card.svg?tz=Asia%2FShanghai&v=1", image.url)
        assertEquals(image.url, image.originalUrl)
        assertEquals(target, image.linkUrl)
        assertEquals("红包卡片", image.alt)
        assertEquals(300f, image.displayWidth)
        assertEquals(300f / 409f, image.ratio!!, .0001f)
        assertEquals(listOf("通关越快奖励越多", "游玩小程序"), blocks.filterIsInstance<ArticleBlock.TextBlock>().map { it.html })
    }

    @Test fun firstUsableSourceWinsAndOnlyItsCandidatesAreCompared() {
        val image = parseArticle(picture("""
            <source type="image/jxl" srcset="/unsupported.jxl 8x">
            <source type="image/svg+xml" media="(min-width: 2000px)" srcset="/wide.svg 9x">
            <source type="IMAGE/SVG+XML" srcset="/one.svg 1x, /two.svg 2x">
            <source type="image/png" srcset="/later.png 5x">
        """.trimIndent(), """src='/fallback.png' srcset='/fallback-large.png 10x'""")).single() as ArticleBlock.Image
        assertEquals("https://linux.do/two.svg", image.url)
        assertEquals(image.url, image.originalUrl)
        val width = parseArticle(picture("""<source srcset="/small.png 300w, /large.png 600w">""")).single() as ArticleBlock.Image
        assertEquals("https://linux.do/large.png", width.url)
    }

    @Test fun invalidSourcesFallBackWithoutLosingAnOrdinaryOriginalOrItsSharpPreview() {
        val invalid = """<source type="image/svg+xml" srcset="javascript:bad 3x, http://unsafe.test/a.svg 4x, /zero.svg 0x, /bad.svg NaNx, /bad.svg 3x extra">"""
        val image = parseArticle(picture(invalid, """src='/fallback.png' srcset='/sharp.png 2x'""")).single() as ArticleBlock.Image
        assertEquals("https://linux.do/sharp.png", image.url)
        assertEquals("https://linux.do/fallback.png", image.originalUrl)
        val next = parseArticle(picture(invalid + """<source srcset="/valid.svg 1x">""")).single() as ArticleBlock.Image
        assertEquals("https://linux.do/valid.svg", next.url)
        assertTrue(parseArticle(picture(invalid)).isEmpty())
    }

    @Test fun aSourceOutsideThePictureOrAfterTheImageCannotOverrideIt() {
        val blocks = parseArticle("""<source srcset="/unrelated.svg"><picture><img src="/fallback.png"><source srcset="/too-late.svg"></picture>""")
        assertEquals("https://linux.do/fallback.png", (blocks.single() as ArticleBlock.Image).url)
        assertTrue(parseArticle("""<source srcset="/unrelated.svg"><img alt="missing">""").isEmpty())
    }

    @Test fun sourceOnlyImagesSurviveQuotesSpoilersAndLightboxOriginals() {
        val html = """<a href="/t/topic/42">${picture("""<source srcset="/card.svg 1x">""")}</a>"""
        val quote = parseArticle("<blockquote><p>$html</p></blockquote>").single() as ArticleBlock.Quote
        val spoiler = parseArticle("""<p><span class="spoiler">$html</span></p>""").single() as ArticleBlock.Spoiler
        for (block in listOf(quote.blocks.single(), spoiler.blocks.single())) {
            val image = block as ArticleBlock.Image
            assertEquals("https://linux.do/card.svg", image.url)
            assertEquals("https://linux.do/t/topic/42", image.linkUrl)
        }
        val lightbox = parseArticle("""<a class="lightbox" href="/original.svg">${picture("""<source srcset="/preview.svg">""")}</a>""").single() as ArticleBlock.Image
        assertEquals("https://linux.do/preview.svg", lightbox.url)
        assertEquals("https://linux.do/original.svg", lightbox.originalUrl)
        assertNull(lightbox.linkUrl)
    }

    @Test fun imgSrcsetWithoutSrcCanSupplyTheSameImageFallback() {
        val image = parseArticle("""<img srcset="/one.png 1x, /two.png 2x" width="100" height="200">""").single() as ArticleBlock.Image
        assertEquals("https://linux.do/two.png", image.url)
        assertEquals(image.url, image.originalUrl)
    }
}
