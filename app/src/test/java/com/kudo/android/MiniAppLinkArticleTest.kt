package com.kudo.android

import com.kudo.android.ui.topic.ArticleBlock
import com.kudo.android.ui.topic.articleImages
import com.kudo.android.ui.topic.parseArticle
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class MiniAppLinkArticleTest {
    private val id = "01a1a1a2-aa95-72c7-85b3-9508dbbffb4f"
    private val app = "https://linux.do/apps/$id"
    private fun link(url: String = app, text: String = url) = """<a href="${url.replace("&", "&amp;")}">$text</a>"""

    @Test fun plainAppLinksBecomeCardsRegardlessOfTheEmbedParameter() {
        for (url in listOf(app, "$app?embed_mode=false", "$app?embed_mode=true", "$app/?room=one#play")) {
            val blocks = parseArticle("<p>${link(url)}</p>")
            assertEquals("普通小程序链接必须生成卡片", url, (blocks.single() as? ArticleBlock.Embed)?.url)
            assertTrue(blocks.articleImages().isEmpty())
        }
        assertEquals(app, (parseArticle(link("/apps/$id", "Bingo")).single() as ArticleBlock.Embed).url)
    }

    @Test fun inlineCardsKeepTheOrderFormattingAndDistinctLaunchParameters() {
        val first = "$app?room=one&room=two&next=%2Ft%2F42#play"
        val second = "$app?room=other#again"
        val blocks = parseArticle("<p><strong>游玩：${link(first, "Bingo")}</strong>，再试${link(second, "另一局")}。<a href='https://example.com/'>说明</a></p>")
        assertEquals(5, blocks.size)
        assertEquals(listOf(first, second), blocks.filterIsInstance<ArticleBlock.Embed>().map { it.url })
        val texts = blocks.filterIsInstance<ArticleBlock.TextBlock>()
        assertEquals(listOf("游玩：", "，再试", "。说明"), texts.map { Jsoup.parseBodyFragment(it.html).text() })
        assertTrue(texts[0].html.contains("<strong>"))
        assertTrue(texts.last().html.contains("href=\"https://example.com/\""))
        val heading = parseArticle("<h2>推荐${link()}</h2>").first() as ArticleBlock.TextBlock
        assertEquals(2, heading.headingLevel)
    }

    @Test fun cardsRemainInsideTheirQuoteListDetailsAndSpoilerContainers() {
        val quote = parseArticle("<blockquote><p>${link()}</p></blockquote>").single() as ArticleBlock.Quote
        assertEquals(app, (quote.blocks.single() as ArticleBlock.Embed).url)
        val list = parseArticle("<ul><li>推荐${link()}</li></ul>").single() as ArticleBlock.ListBlock
        assertEquals(app, (list.items.single().blocks.last() as ArticleBlock.Embed).url)
        val details = parseArticle("<details><summary>更多</summary><p>${link()}</p></details>").single() as ArticleBlock.Details
        assertEquals(app, (details.blocks.single() as ArticleBlock.Embed).url)
        val spoiler = parseArticle("<p>隐藏<span class='spoiler'>${link()}</span>尾部</p>")
        assertEquals(3, spoiler.size)
        assertEquals(app, ((spoiler[1] as ArticleBlock.Spoiler).blocks.single() as ArticleBlock.Embed).url)
    }

    @Test fun ordinaryDirectoryAndInvalidLinksStayTextAndCodeExamplesStayCode() {
        for (url in listOf("https://linux.do/apps/", "https://linux.do/apps/x", "$app/extra", "http://linux.do/apps/$id",
            "https://linux.do.evil.test/apps/$id", "https://linux.do:444/apps/$id", "https://user@linux.do/apps/$id", "https://example.com/")) {
            val blocks = parseArticle("<p>${link(url)}</p>")
            assertTrue(url, blocks.single() is ArticleBlock.TextBlock)
        }
        assertTrue(parseArticle("<p><code>${link()}</code></p>").single() is ArticleBlock.TextBlock)
        assertTrue(parseArticle("<pre><code>${link()}</code></pre>").single() is ArticleBlock.Code)
    }

    @Test fun imageLinksKeepTheirImageAndTheOriginalAppDestination() {
        for (body in listOf("<img src='/card.png' width='300' height='400'>",
            "<picture><source type='image/svg+xml' srcset='/card.svg 1x'><img width='300' height='400'></picture>")) {
            val blocks = parseArticle("<p>${link(text = body)}</p>")
            assertEquals(app, (blocks.single() as ArticleBlock.Image).linkUrl)
            assertTrue(blocks.none { it is ArticleBlock.Embed })
        }
    }
}
