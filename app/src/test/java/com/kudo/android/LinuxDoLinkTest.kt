package com.kudo.android

import com.kudo.android.ui.LinuxDoDestination.*
import com.kudo.android.ui.LinuxDoLink
import org.junit.Assert.*
import org.junit.Test

class LinuxDoLinkTest {
    @Test fun systemMiniAppLinksKeepTheirFullLaunchAddressInsteadOfGoingHome() {
        val url = "https://linux.do/apps/01a1966a-f4a0-7b43-ad9c-c46b7f0e0281?hb=fixture-packet&room=one&room=two&next=%2Ft%2F123#play"
        assertEquals(MiniApp(url), LinuxDoLink.parse(url))
        assertEquals(MiniApp(url), LinuxDoLink.parseContent(url.removePrefix("https://linux.do")))
        val encoded = url.replace("fixture-packet", "a%2Bb%2Fc").replace("#play", "#%E4%BD%A0")
        assertEquals(MiniApp(encoded), LinuxDoLink.parse(encoded))
    }

    @Test fun directoryAndMiniAppLinksHaveSeparateDestinations() {
        assertEquals(Apps, LinuxDoLink.parseContent("/apps/?q=2048"))
        assertEquals(Apps, LinuxDoLink.parse("https://linux.do/apps"))
        val url = "https://linux.do/apps/01a1966a-f4a0-7b43-ad9c-c46b7f0e0281?room=one#play"
        assertEquals(MiniApp(url), LinuxDoLink.parseContent(url))
        assertNull(LinuxDoLink.parseContent("https://other.test/apps"))
    }

    @Test fun onlyExactOfficialHttpsAppsBecomeMiniAppDeepLinkTargets() {
        val path = "/apps/01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"
        for (url in listOf("http://linux.do$path", "https://linux.do/apps/invalid", "https://linux.do$path/other",
            "https://linux.do/apps/01a1966a%2Ff4a0-7b43-ad9c-c46b7f0e0281")) {
            assertEquals(url, Home, LinuxDoLink.parse(url))
            assertNull(url, LinuxDoLink.parseContent(url))
        }
        for (url in listOf("https://linux.do.evil.test$path", "https://linux.do:444$path", "https://user@linux.do$path")) {
            assertNull(url, LinuxDoLink.parse(url))
            assertNull(url, LinuxDoLink.parseContent(url))
        }
    }
    @Test fun connectContentLinksHaveANativeDestinationButOtherOriginsDoNot() {
        listOf("https://connect.linux.do", "https://CONNECT.LINUX.DO:443/?from=topic#level",
            "http://connect.linux.do:80/", "//connect.linux.do/help", "https://connect.linux.do/t/123").forEach {
            assertEquals(it, Trust, LinuxDoLink.parseContent(it))
            assertNull(it, LinuxDoLink.parse(LinuxDoLink.webUrl(it).orEmpty()))
        }
        listOf("https://connect.linux.do.evil.test/", "https://notconnect.linux.do/",
            "https://cdk.linux.do/", "https://connect.linux.do:444/", "http://connect.linux.do:443/",
            "https://user@connect.linux.do/", "https://connect.linux.do@evil.test/").forEach {
            assertNull(it, LinuxDoLink.parseContent(it))
        }
    }

    @Test fun topicsAcceptSlugShortPathAndFloor() {
        assertEquals(Topic(3001000), LinuxDoLink.parse("https://linux.do/t/topic/3001000"))
        assertEquals(Topic(3001000, 8), LinuxDoLink.parse("https://linux.do/t/topic/3001000/8"))
        assertEquals(Topic(3001000, 8), LinuxDoLink.parse("https://linux.do/t/3001000/8/?foo=1#post_8"))
        assertEquals(Topic(3001000), LinuxDoLink.parse(" https://linux.do/t/3001000/ "))
    }

    @Test fun userSubpathsAlwaysOpenTheProfile() {
        listOf("", "/", "/summary/", "/activity/replies/").forEach {
            assertEquals(User("stingysheep"), LinuxDoLink.parse("https://linux.do/u/stingysheep$it"))
        }
        assertEquals(User("中文用户"), LinuxDoLink.parse("https://linux.do/u/%E4%B8%AD%E6%96%87%E7%94%A8%E6%88%B7/summary"))
    }

    @Test fun otherSitePathsAndMalformedTargetsGoHome() {
        listOf("/", "/latest", "/u/", "/t/topic/0", "/t/topic/999999999999",
            "/t/topic/12/0", "/t/topic/12/nope", "/t/topic/12/8/extra").forEach {
            assertEquals(it, Home, LinuxDoLink.parse("https://linux.do$it"))
        }
        assertEquals(Home, LinuxDoLink.parse("http://linux.do/latest"))
    }

    @Test fun categoriesAcceptNumericSlugAndNestedPaths() {
        listOf("/c/4", "/c/develop/4/", "/c/parent/develop/4?order=latest").forEach {
            assertEquals(it, Category(4), LinuxDoLink.parse("https://linux.do$it"))
        }
        listOf("/c/0", "/c/develop/999999999999", "/c/develop/4/l/latest").forEach {
            assertEquals(it, Home, LinuxDoLink.parse("https://linux.do$it"))
        }
    }

    @Test fun tagsAcceptSlugOnlyAndLinuxDoNumericSuffix() {
        assertEquals(Tag("193-tag", 193), LinuxDoLink.parse("https://linux.do/tag/193-tag/193"))
        assertEquals(Tag("193-tag", 193), LinuxDoLink.parseContent("/tag/193-tag/193"))
        assertEquals(Tag("network"), LinuxDoLink.parse("https://linux.do/tag/network"))
        listOf("/tag/", "/tag/network/0", "/tag/network/193/extra", "/tag/network/l/latest").forEach {
            assertEquals(it, Home, LinuxDoLink.parse("https://linux.do$it"))
        }
    }

    @Test fun unrelatedOriginsAndEncodedSlashesAreNotRoutedAsProfiles() {
        listOf("https://linux.do.evil.test/t/topic/1", "https://example.com/u/test", "javascript:alert(1)",
            "https://user@linux.do/t/1", "https://linux.do:444/t/1").forEach { assertNull(LinuxDoLink.parse(it)) }
        assertEquals(Home, LinuxDoLink.parse("https://linux.do/u/bad%2Fname"))
    }

    @Test fun articleRoutingKeepsUnknownPagesForTheWebReader() {
        assertEquals(Topic(3001000, 8), LinuxDoLink.parseContent("/t/topic/3001000/8?source=article#post_8"))
        assertEquals(User("stingysheep"), LinuxDoLink.parseContent("/u/stingysheep/activity/"))
        assertEquals(Category(4), LinuxDoLink.parseContent("/c/develop/4"))
        assertEquals(Home, LinuxDoLink.parseContent("https://linux.do/"))
        listOf("https://example.org/help", "/guidelines", "/c/develop", "/t/not-a-topic", "https://linux.do.evil.test/t/1").forEach {
            assertNull(it, LinuxDoLink.parseContent(it))
        }
        assertEquals("https://linux.do/guidelines", LinuxDoLink.webUrl("/guidelines"))
        assertEquals("https://example.org/help", LinuxDoLink.webUrl("//example.org/help"))
        listOf("javascript:alert(1)", "file:///etc/passwd", "intent://example.org", "https://user:password@linux.do/", " ").forEach {
            assertNull(it, LinuxDoLink.webUrl(it))
        }
    }
}
