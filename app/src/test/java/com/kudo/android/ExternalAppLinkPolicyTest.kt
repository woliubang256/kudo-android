package com.kudo.android

import com.kudo.android.ui.*
import org.junit.Assert.*
import org.junit.Test

class ExternalAppLinkPolicyTest {
    private val share = "https://pan.quark.cn/s/Abc123?pwd=1234#files"
    private val scheme = "qklink://www.uc.cn/b20b84fd735a8dd3f7541129bacc4e9a?action=open_url&url=https%3A%2F%2Fpan.quark.cn%2Fs%2FAbc123%3Fpwd%3D1234%23files"

    @Test fun quarkOnlyMatchesSharePagesAndPreservesAccessParameters() {
        assertEquals(share, ExternalAppLinkPolicy.quarkShare(share))
        assertEquals(ExternalAppLinkPolicy.QUARK_PACKAGE, ExternalAppLinkPolicy.targetPackage(share))
        assertNull(ExternalAppLinkPolicy.targetPackage(share.replace("https:", "http:")))
        listOf("https://pan.quark.cn/", "https://pan.quark.cn/s/", "https://pan.quark.cn/s/a/extra",
            "https://pan.quark.cn.evil.test/s/a", "https://user@pan.quark.cn/s/a",
            "https://pan.quark.cn:444/s/a", "https://example.org/s/a").forEach {
            assertNull(it, ExternalAppLinkPolicy.quarkShare(it))
        }
    }

    @Test fun publishedQuarkOpenUrlSupportsWebFallbackWithoutAllowingOtherCommands() {
        assertEquals(scheme, ExternalAppLinkPolicy.contentUrl(scheme))
        assertEquals(share, ExternalAppLinkPolicy.fallbackUrl(scheme))
        assertNotNull(ExternalAppLinkPolicy.quarkScheme(scheme.replace("qklink:", "uclink:")))
        listOf(scheme.replace("open_url", "other_command"), scheme + "&action=other_command",
            scheme.replace("www.uc.cn/", "www.uc.cn.evil.test/"),
            scheme.replace("pan.quark.cn", "evil.test"), "quark://arbitrary", "intent://example",
            "javascript:alert(1)", "file:///sdcard/test", "content://test").forEach {
            assertNull(it, ExternalAppLinkPolicy.contentUrl(it))
        }
    }

    @Test fun relativeAndSiteUrlsKeepTheirExistingMeaning() {
        assertEquals("https://linux.do/t/123", ExternalAppLinkPolicy.contentUrl("/t/123"))
        assertEquals("https://example.org/help", ExternalAppLinkPolicy.fallbackUrl("https://example.org/help"))
    }

    @Test fun siteBoundaryIncludesSubdomainsButExcludesLookalikesAndUnsafeUrls() {
        listOf("/t/123", "https://linux.do/", "http://connect.linux.do/help",
            "https://a.b.linux.do/", "https://LINUX.DO/").forEach {
            assertTrue(it, ExternalAppLinkPolicy.isSiteUrl(it))
        }
        listOf("https://linux.do.evil.test/", "https://notlinux.do/", "https://example.org/?next=linux.do",
            "https://user@linux.do/", "javascript:alert(1)", scheme).forEach {
            assertFalse(it, ExternalAppLinkPolicy.isSiteUrl(it))
        }
    }

    @Test fun onlyMainFrameUserClicksCanLeaveTheWebView() {
        assertTrue(ExternalAppLinkPolicy.canOpenFromWeb(true, true, false))
        assertFalse(ExternalAppLinkPolicy.canOpenFromWeb(false, true, false))
        assertFalse(ExternalAppLinkPolicy.canOpenFromWeb(true, false, false))
        assertFalse(ExternalAppLinkPolicy.canOpenFromWeb(true, true, true))
    }
}
