package com.kudo.android

import com.kudo.android.ui.topic.*
import org.junit.Assert.*
import org.junit.Test

class OneboxPreviewTest {
    @Test fun miniAppIframePreservesItsLaunchUrlWithoutBecomingAnArticleImage() {
        val url = "https://linux.do/apps/01a19ba5-b9d2-766f-9fb9-b7eddc10e0c7?embed_mode=true&hb=fixture-packet&source=post#detail"
        val blocks = parseArticle("""<iframe src="${url.replace("&", "&amp;")}" width="100%" height="700" frameborder="0" allow="fullscreen; clipboard-write" allowfullscreen=""></iframe>""")
        assertEquals(url, (blocks.single() as ArticleBlock.Embed).url)
        assertTrue(blocks.articleImages().isEmpty())
        assertEquals("https://example.com/media", (parseArticle("""<iframe src="https://example.com/media"></iframe>""").single() as ArticleBlock.Embed).url)
    }

    private val github = """<aside class="onebox githubrepo" data-onebox-src="https://github.com/Kuddev/pebrel"><header class="source"><a href="https://github.com/Kuddev/pebrel">github.com</a></header><article class="onebox-body"><div class="github-row"><img class="thumbnail" src="https://cdn3.ldstatic.com/preview.png"><h3><a href="https://github.com/Kuddev/pebrel">GitHub - Kuddev/pebrel: AI-native terminal</a></h3><p><span class="github-repo-description">AI-native, GPU-accelerated terminal emulator for Windows.</span></p></div></article></aside>"""
    @Test fun githubUsesTheHeadingAndCachedMetadataInsteadOfTheHeaderDomain() {
        val blocks = parseArticle(github)
        val preview = blocks.single() as ArticleBlock.Embed
        assertEquals("https://github.com/Kuddev/pebrel", preview.url)
        assertEquals("GitHub - Kuddev/pebrel: AI-native terminal", preview.title)
        assertEquals("AI-native, GPU-accelerated terminal emulator for Windows.", preview.description)
        assertEquals("https://cdn3.ldstatic.com/preview.png", preview.image)
        assertEquals("github.com", preview.site)
        assertTrue(blocks.articleImages().isEmpty())
    }
    @Test fun incompleteAndUnsafeMetadataKeepOnlyAUsableLink() {
        val block = parseArticle("""<aside class="onebox" data-onebox-src="javascript:bad()"><header><a href="https://example.com/article">文章</a></header><article class="onebox-body"><img class="thumbnail" src="http://insecure.test/p.png"><p>简短介绍</p></article></aside>""").single() as ArticleBlock.Embed
        assertEquals("https://example.com/article", block.url)
        assertNull(block.image)
        assertEquals("简短介绍", block.description)
        assertTrue(parseArticle("""<aside class="onebox"><a href="javascript:bad()">错误链接</a></aside>""").isEmpty())
        assertEquals(ArticleBlock.Embed("https://example.com/", "普通链接"),
            parseArticle("""<aside class="onebox"><a href="https://example.com">普通链接</a></aside>""").single())
    }
}
