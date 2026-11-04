package com.kudo.android

import com.kudo.android.data.*
import com.kudo.android.ui.topic.ArticleBlock
import com.kudo.android.ui.topic.ArticleCalloutType
import com.kudo.android.ui.topic.articleHeadings
import com.kudo.android.ui.topic.hasThemeTocMarker
import com.kudo.android.ui.topic.topicTocHeadings
import com.kudo.android.ui.topic.parseArticle
import com.kudo.android.ui.topic.parseInlineArticle
import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContentParserTest {
    @Test fun linkedImageKeepsTargetSeparateFromPreviewAndOriginal() {
        val image = parseArticle("""<p><a href="https://hb.unsnow.org/hb/fixture?source=post&amp;x=1#detail"><span><img src="/card.svg" width="300" height="409" srcset="/sharp.svg 2x" alt="红包卡片"></span></a></p>""").single() as ArticleBlock.Image
        assertEquals("https://hb.unsnow.org/hb/fixture?source=post&x=1#detail", image.linkUrl)
        assertEquals("https://linux.do/sharp.svg", image.url)
        assertEquals("https://linux.do/card.svg", image.originalUrl)
        assertEquals("红包卡片", image.alt)
        assertEquals(300f, image.displayWidth)
    }

    @Test fun imageLinksNormalizeRelativeTargetsAndRejectInvalidSchemesAndCredentials() {
        fun image(href: String) = parseArticle("""<a href="$href"><img src="/card.svg"></a>""").single() as ArticleBlock.Image
        assertEquals("https://linux.do/t/topic/42?source=card#post", image("/t/topic/42?source=card#post").linkUrl)
        assertEquals("https://example.com/card", image("//example.com/card").linkUrl)
        for (href in listOf("", "javascript:alert(1)", "data:text/html,test", "https://user:pass@example.com/card")) {
            assertNull(image(href).linkUrl)
        }
        val lightbox = parseArticle("""<a class="lightbox" href="/original.svg"><img src="/card.svg"></a>""").single() as ArticleBlock.Image
        assertNull(lightbox.linkUrl)
        assertEquals("https://linux.do/original.svg", lightbox.originalUrl)
    }

    @Test fun linkedImagesInQuotesAndSpoilersKeepTheirTargets() {
        val html = """<a href="/t/topic/42"><img src="/card.svg"></a>"""
        val quote = parseArticle("<blockquote><p>$html</p></blockquote>").single() as ArticleBlock.Quote
        val spoiler = parseArticle("""<p><span class="spoiler">$html</span></p>""").single() as ArticleBlock.Spoiler
        for (image in listOf(quote.blocks.single(), spoiler.blocks.single()).filterIsInstance<ArticleBlock.Image>()) {
            assertEquals("https://linux.do/t/topic/42", image.linkUrl)
        }
        assertEquals(1, quote.blocks.size)
        assertEquals(1, spoiler.blocks.size)
    }

    @Test fun actualSiteCalloutKeepsSameParagraphBodyAndCode() {
        val callout = parseArticle("""<blockquote><p>[!warning]+ 别看了<br>快<strong>折起来</strong>。<br>第二行</p><pre><code>&gt; [!warning]+ 别看了</code></pre></blockquote>""").single() as ArticleBlock.Callout
        assertEquals(ArticleCalloutType.WARNING, callout.type)
        assertEquals("别看了", callout.title)
        assertTrue(callout.open)
        assertEquals("快折起来。\n第二行", parseInlineArticle((callout.blocks[0] as ArticleBlock.TextBlock).html, Color.Blue).text.text)
        assertTrue((callout.blocks[0] as ArticleBlock.TextBlock).html.contains("<strong>折起来</strong>"))
        assertTrue(callout.blocks[1] is ArticleBlock.Code)
        val ordinary = parseArticle("<blockquote><p>前言<br>[!note]<br>普通引用</p></blockquote>").single()
        assertTrue(ordinary is ArticleBlock.Quote)
    }

    @Test fun calloutAliasesAndExplicitFoldingKeepTheirMeaning() {
        assertEquals(ArticleCalloutType.TIP, ArticleCalloutType.of("IMPORTANT"))
        assertEquals(ArticleCalloutType.ABSTRACT, ArticleCalloutType.of("tldr"))
        assertEquals(ArticleCalloutType.CAUTION, ArticleCalloutType.of("CAUTION"))
        val fixed = parseArticle("<blockquote><p>[!note]<br>正文</p></blockquote>").single() as ArticleBlock.Callout
        assertFalse(fixed.foldable)
        assertTrue(fixed.open)
        val folded = parseArticle("<blockquote><p>[!success]- 非常成功<br>确实如此。</p></blockquote>").single() as ArticleBlock.Callout
        assertTrue(folded.foldable)
        assertFalse(folded.open)
        assertEquals("非常成功", folded.title)
    }

    @Test fun markdownHeadingsExposeLevelsAndPlainTitlesForTopicToc() {
        val blocks = parseArticle("<h1>总览 <strong>一</strong></h1><h5>细节</h5><blockquote><h2>引用标题</h2></blockquote><details open><summary>折叠区</summary><h3>正文标题</h3></details>")
        val headings = blocks.articleHeadings()
        assertEquals(listOf(1, 5, 3), headings.map { it.level })
        assertEquals(listOf("总览 一", "细节", "正文标题"), headings.map { it.title })
        val h5 = blocks[1] as ArticleBlock.TextBlock
        assertTrue(h5.heading)
        assertEquals(5, h5.headingLevel)
        assertEquals("细节", h5.headingText)
    }

    @Test fun themeTocRequiresMarkerAtStartOfPost() {
        assertTrue(hasThemeTocMarker("<div data-theme-toc=\"true\"></div><h2>章节</h2><h3>小节</h3>"))
        assertFalse(hasThemeTocMarker("<p>前言</p><div data-theme-toc=\"true\"></div><h2>章节</h2>"))
        assertFalse(hasThemeTocMarker("<div data-theme-toc=\"false\"></div><h2>章节</h2>"))
    }

    @Test fun topicTocUsesOnlyMarkedH2AndH3Headings() {
        assertEquals(listOf(2, 3), topicTocHeadings("<div data-theme-toc=\"true\"></div><h1>封面</h1><h2>章节</h2><h3>小节</h3><h4>细节</h4>").map { it.level })
        assertTrue(topicTocHeadings("<h2>章节</h2><h3>小节</h3>").isEmpty())
        assertTrue(topicTocHeadings("<div data-theme-toc=\"true\"></div><h1>封面</h1>").isEmpty())
    }
    @Test fun htmlTablesRemainTablesInsteadOfCodeBlocks() {
        val table = parseArticle("""
            <table><thead><tr><th>项目</th><th>我的情况</th></tr></thead>
            <tbody><tr><td>注册时间</td><td>8 月 7 日</td></tr><tr><td>卡片申请通过</td><td>8 月 19 日</td></tr></tbody></table>
        """.trimIndent()).single() as ArticleBlock.Table
        assertEquals(listOf(true, false, false), table.rows.map { it.header })
        assertEquals(listOf("项目", "我的情况"), table.rows.first().cells.map { org.jsoup.Jsoup.parse(it).text() })
        assertEquals("8 月 19 日", org.jsoup.Jsoup.parse(table.rows.last().cells.last()).text())
    }

    @Test fun animatedSmallAttachmentKeepsHtmlWidthAndOriginalAnimationInsteadOfStaticSrcset() {
        val image = parseArticle("""<img class="animated" src="/small.avif" width="153" height="153" srcset="/still.png 2x">""").single() as ArticleBlock.Image
        assertEquals(153f, image.displayWidth)
        assertEquals("https://linux.do/small.avif", image.url)
        val lightbox = parseArticle("""<a class="lightbox" href="/long.png"><img src="/preview.png" width="90" height="500"></a>""").single() as ArticleBlock.Image
        assertNull(lightbox.displayWidth)
    }
    @Test fun tagsAcceptNamesAndObjectsWithoutDisplayingRawJson() {
        val page = DiscourseParser.topicPage(JSONObject("""{"topic_list":{"topics":[{"id":1,"title":"标签测试","tags":["Android",{"id":1461,"name":"纯水","slug":"1461-tag"},null,{"id":2},""]}]}}"""))
        assertEquals(listOf("Android", "纯水"), page.topics.single().tags)
    }
    @Test fun topicJoinsAuthorAndNormalizesNullFields() {
        val json = JSONObject("""{"users":[{"id":9,"username":"doer","name":null,"avatar_template":"/avatar/{size}.png"}],"topic_list":{"topics":[{"id":12,"title":"测试","posts_count":4,"image_url":null,"posters":[{"user_id":9}]}],"more_topics_url":"/latest.json?page=1"}}""")
        val page = DiscourseParser.topicPage(json)
        assertEquals("doer", page.topics.single().author!!.displayName)
        assertEquals("", page.topics.single().image)
        assertEquals(3, page.topics.single().replies)
        assertEquals("/latest.json?page=1", page.next)
    }
    @Test fun detailsRetainIdsOfNotYetLoadedPosts() {
        val detail = DiscourseParser.detail(JSONObject("""{"id":1,"title":"长帖","post_stream":{"stream":[10,11,12],"posts":[{"id":10,"post_number":1,"username":"u","cooked":"<p>Hello</p>"}]}}"""))
        assertEquals(1, detail.posts.size)
        assertEquals(listOf(10, 11, 12), detail.stream)
    }
    @Test fun likedPostAndBookmarkAreRecognized() {
        val post = DiscourseParser.post(JSONObject("""{"id":7,"post_number":2,"username":"u","actions_summary":[{"id":2,"count":4,"acted":true}],"bookmarks":[{"id":90}]}"""))
        assertTrue(post.liked)
        assertEquals(4, post.likes)
        assertEquals(90, post.bookmarkId)
    }
    @Test fun richContentRetainsCodeImagesQuotesAndRejectsScripts() {
        val blocks = parseArticle("""<p>正文 <strong>加粗</strong></p><blockquote><p>引用</p></blockquote><pre><code>val x = 1
println(x)</code></pre><p><img src="/uploads/image.png" width="800" height="600" alt="图"></p><script>alert(1)</script><iframe src="javascript:alert(1)"></iframe>""")
        assertEquals(4, blocks.size)
        assertTrue(((blocks[1] as ArticleBlock.Quote).blocks.single() as ArticleBlock.TextBlock).quote)
        assertTrue((blocks[2] as ArticleBlock.Code).text.contains("\n"))
        assertEquals("https://linux.do/uploads/image.png", (blocks[3] as ArticleBlock.Image).url)
    }

    @Test fun obsidianCalloutsBecomeTypedBlocksAndOrdinaryQuotesStayQuotes() {
        val blocks = parseArticle("""
            <blockquote><p>[!note]</p><p>说明 <strong>内容</strong></p></blockquote>
            <blockquote><p>普通引用</p></blockquote>
            <blockquote><p>[!warning]+ 自定义标题</p><p>警告正文</p></blockquote>
        """.trimIndent())
        assertEquals(3, blocks.size)
        val note = blocks[0] as ArticleBlock.Callout
        assertEquals(ArticleCalloutType.NOTE, note.type)
        assertEquals("说明 <strong>内容</strong>", (note.blocks.single() as ArticleBlock.TextBlock).html)
        assertEquals("普通引用", parseInlineArticle(((blocks[1] as ArticleBlock.Quote).blocks.single() as ArticleBlock.TextBlock).html, androidx.compose.ui.graphics.Color.Blue).text.text)
        val warning = blocks[2] as ArticleBlock.Callout
        assertEquals(ArticleCalloutType.WARNING, warning.type)
        assertEquals("自定义标题", warning.title)
        assertTrue(warning.open)
    }
    @Test fun plainTextDecodesEntities() { assertEquals("A & B", plainText("<p>A &amp; B</p>")) }

    @Test fun standaloneEmojiKeepsItsImageInsteadOfOnlyTheShortcode() {
        val html = """<img src="https://cdn.ldstatic.com/images/emoji/twemoji/distorted_face.png?v=15" class="emoji" alt=":distorted_face:">"""
        val block = parseArticle(html).single() as ArticleBlock.TextBlock
        assertTrue("表情必须保留图片来源，不能只留下 :distorted_face:", block.html.contains("distorted_face.png"))
        assertTrue(block.html.contains("class=\"emoji\""))
    }

    @Test fun inlineEmojisKeepOrderNamesLinksAndHttpsSources() {
        val content = parseInlineArticle("""前<strong>粗体</strong><img class="emoji" src="/images/emoji/twemoji/distorted_face.png?v=15" alt=":distorted_face:"><a href="https://linux.do/t/1">链接<img class="emoji" src="//cdn.ldstatic.com/custom.gif" alt=":custom:"></a>后""", Color.Blue)
        assertEquals("前粗体\uFFFC链接\uFFFC后", content.text.text)
        assertEquals(listOf(":distorted_face:", ":custom:"), content.emojis.values.map { it.name })
        assertEquals(listOf("https://linux.do/images/emoji/twemoji/distorted_face.png?v=15", "https://cdn.ldstatic.com/custom.gif"), content.emojis.values.map { it.url })
        assertTrue(content.text.spanStyles.isNotEmpty())
        assertTrue(content.text.getLinkAnnotations(0, content.text.length).isNotEmpty())
    }

    @Test fun repeatedAndQuotedEmojisStayInlineWhileOrdinaryImagesStayBlocks() {
        val blocks = (parseArticle("""<blockquote><p>A<img class="emoji" src="/one.png" alt=":one:"><img class="emoji" src="/one.png" alt=":one:">B<img src="/photo.png"></p></blockquote>""").single() as ArticleBlock.Quote).blocks
        val text = blocks[0] as ArticleBlock.TextBlock
        assertTrue(text.quote)
        val inline = parseInlineArticle(text.html, Color.Blue)
        assertEquals("A\uFFFC\uFFFCB", inline.text.text)
        assertEquals(2, inline.emojis.size)
        assertEquals("https://linux.do/photo.png", (blocks[1] as ArticleBlock.Image).url)
    }

    @Test fun invalidEmojiSourcesKeepReadableAlternativeText() {
        val content = parseInlineArticle("""<img class="emoji" src="javascript:alert(1)" alt=":bad:"><img class="emoji" src="http://example.com/emoji.png" alt=":insecure:">""", Color.Blue)
        assertEquals(":bad::insecure:", content.text.text)
        assertTrue(content.emojis.isEmpty())
    }

    @Test fun wideScreenshotsKeepTheirActualAspectRatio() {
        val block = parseArticle("""<img src="/wide.png" width="1688" height="339">""").single() as ArticleBlock.Image
        assertEquals("宽图容器不能被比例上限撑高", 1688f / 339f, requireNotNull(block.ratio), 0.0001f)
    }

    @Test fun lightboxMetadataDoesNotBecomeBodyTextButAuthorCaptionsSurvive() {
        val blocks = parseArticle("""<div class="lightbox-wrapper"><a class="lightbox" href="/original.png"><img src="/preview.png" width="1688" height="339" alt="示例"><div class="meta"><span class="filename">20261008230932_14_506</span><span class="informations">1688×339 22.8 KB</span></div></a></div><p>我写的图片说明</p><div class="meta">正常正文</div>""")
        assertEquals("只应有图片、作者说明与普通正文", 3, blocks.size)
        assertTrue(blocks.first() is ArticleBlock.Image)
        assertEquals(listOf("我写的图片说明", "正常正文"), blocks.filterIsInstance<ArticleBlock.TextBlock>().map { it.html })
        val image = blocks.first() as ArticleBlock.Image
        assertEquals("https://linux.do/original.png", image.originalUrl)
        assertEquals("20261008230932_14_506", image.filename)
        assertEquals("1688×339", image.dimensions)
        assertEquals("22.8 KB", image.sizeLabel)
    }

    @Test fun tallImagesAndMissingOrInvalidDimensionsDoNotUseAnInventedAspectRatio() {
        val tall = parseArticle("""<img src="/tall.png" width="200" height="4000">""").single() as ArticleBlock.Image
        assertEquals(0.05f, requireNotNull(tall.ratio), 0.0001f)
        for (attributes in listOf("", "width=\"0\" height=\"100\"", "width=\"200\" height=\"-2\"", "width=\"NaN\" height=\"2\"")) {
            val image = parseArticle("<img src=\"/unknown.png\" $attributes>").single() as ArticleBlock.Image
            assertNull(image.ratio)
            assertEquals(image.url, image.originalUrl)
        }
    }

    @Test fun inlineLightboxDropsOnlyAutomaticMetadataAndRejectsInsecureOriginals() {
        val blocks = parseArticle("""<p>说明<a class="lightbox" href="javascript:alert(1)"><img src="/safe.png"><span class="meta"><span class="filename">隐藏文件名</span></span></a><img class="emoji" src="/emoji.png" alt=":ok:">保留图注</p>""")
        val text = blocks.filterIsInstance<ArticleBlock.TextBlock>().single().html
        assertFalse(text.contains("隐藏文件名"))
        assertTrue(text.contains("保留图注"))
        assertTrue(text.contains("class=\"emoji\""))
        assertEquals("https://linux.do/safe.png", blocks.filterIsInstance<ArticleBlock.Image>().single().originalUrl)
    }

    @Test fun longReplyUsesTheSharpestSitePreviewAndRetainsTheFullAttachment() {
        val image = parseArticle("""<a class="lightbox" href="https://cdn.test/original.jpeg"><img src="https://cdn.test/90x500.jpeg" width="90" height="500" srcset="https://cdn.test/90x500.jpeg, https://cdn.test/135x750.jpeg 1.5x, https://cdn.test/180x1000.jpeg 2x"></a>""").single() as ArticleBlock.Image
        assertEquals("不能继续拉伸最低清晰度的90×500缩略图", "https://cdn.test/180x1000.jpeg", image.url)
        assertEquals("https://cdn.test/original.jpeg", image.originalUrl)
        assertEquals(90f / 500f, requireNotNull(image.ratio), 0.0001f)
    }

    @Test fun previewCandidatesIgnoreInsecureAndInvalidDescriptorsAndKeepFallbackOriginal() {
        val image = parseArticle("""<img src="/base.png" width="100" height="500" srcset="http://insecure.test/4.png 4x, javascript:alert(1) 9x, /zero.png 0x, /invalid.png NaNx, /negative.png -1x, /sharp.png 2x, /infinite.png Infinityx, /malformed.png 3x extra">""").single() as ArticleBlock.Image
        assertEquals("https://linux.do/sharp.png", image.url)
        assertEquals("https://linux.do/base.png", image.originalUrl)
        val fallback = parseArticle("""<img src="/base.png" srcset="http://insecure.test/a.png 2x, /bad.png zero">""").single() as ArticleBlock.Image
        assertEquals("https://linux.do/base.png", fallback.url)
    }

    @Test fun widthDescriptorsUseTheDeclaredWidthAndLowDensityCannotDowngradeTheSource() {
        val sharp = parseArticle("""<img src="/base.png" width="200" height="1000" srcset="/large.png 400w, /medium.png 300w">""").single() as ArticleBlock.Image
        assertEquals("https://linux.do/large.png", sharp.url)
        val small = parseArticle("""<img src="/base.png" width="200" height="1000" srcset="/small.png 100w, /low.png 0.5x">""").single() as ArticleBlock.Image
        assertEquals("https://linux.do/base.png", small.url)
    }
}
