package com.kudo.android.data

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubUpdatesTest {
    @Test fun onlyUploadedOfficialVersionMatchedApkCanBeDownloaded() {
        val name = "kodo_1.2.3_release_arm64-v8a.apk"
        val url = "$KudoSourceUrl/releases/download/v1.2.3/$name"
        fun asset() = JSONObject().put("name", name).put("browser_download_url", url)
            .put("state", "uploaded").put("size", 123L).put("digest", "sha256:" + "a".repeat(64))
        fun parse(value: JSONObject) = parseRelease(JSONObject().put("tag_name", "v1.2.3")
            .put("assets", org.json.JSONArray().put(value)))
        assertEquals(GitHubApk(name, url, 123, "a".repeat(64)), parse(asset()).apk)
        for (bad in listOf(asset().put("name", "debug.apk"), asset().put("state", "new"),
            asset().put("size", 0), asset().put("browser_download_url", "http://github.com/evil.apk"),
            asset().put("browser_download_url", url.replace("v1.2.3/", "v1.2.2/")),
            asset().put("browser_download_url", url.replace("woliubang256", "other")))) {
            assertNull(parse(bad).apk)
        }
        assertNull(parseRelease(JSONObject().put("tag_name", "v1.2.3")).apk)
        assertNull(parse(asset().put("digest", "invalid")).apk!!.sha256)
    }

    @Test fun versionComparisonUsesNumbersAndStableReleaseRules() {
        val release = parseRelease(JSONObject("""{"tag_name":"v0.10.0"}"""))
        assertTrue(release.isNewerThan("0.9.999"))
        assertFalse(release.isNewerThan("0.10.0"))
        assertFalse(release.isNewerThan("1.0.0"))
        for (bad in listOf("v01.2.3", "v1.1000.0", "v2100.0.0", "v1.2.3-rc.1", "v1.2.3\n", "1.2.3")) {
            assertThrows(UpdateFailure::class.java) { parseRelease(JSONObject().put("tag_name", bad)) }
        }
        for (flag in listOf("draft", "prerelease")) {
            assertThrows(UpdateFailure::class.java) { parseRelease(JSONObject().put("tag_name", "v1.2.3").put(flag, true)) }
        }
    }

    @Test fun publicRequestPreservesChangelogAndNeverIncludesCredentials() = runBlocking {
        MockWebServer().use { server ->
            val notes = "## 新增\n- 检查更新\n\n## 修复\n- 修复头像裁切"
            server.enqueue(MockResponse().setBody(JSONObject().put("tag_name", "v1.2.3").put("body", notes)
                .put("name", "酷DO 1.2.3").put("published_at", "2026-10-11T01:00:00Z")
                .put("html_url", "https://invalid.test/").toString()))
            val release = GitHubUpdates(endpoint = server.url("/latest")).latest()
            assertEquals(notes, release.notes)
            assertEquals("$KudoSourceUrl/releases/tag/v1.2.3", release.url)
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        }
    }

    @Test fun missingPrivateRateLimitedAndMalformedReleasesAreNotLatestSuccesses() = runBlocking {
        MockWebServer().use { server ->
            for ((code, body, text) in listOf(Triple(404, "{}", "暂无公开版本"), Triple(429, "{}", "限制请求"),
                Triple(403, "{}", "限制请求"), Triple(500, "{}", "暂不可用"), Triple(200, "invalid", "无法读取"))) {
                server.enqueue(MockResponse().setResponseCode(code).setBody(body))
                try { GitHubUpdates(endpoint = server.url("/latest")).latest(); fail("应报告错误") }
                catch (e: UpdateFailure) { assertTrue(e.message.contains(text)) }
            }
        }
    }

    @Test fun unavailableNetworkReportsConnectionFailure() = runBlocking {
        val server = MockWebServer()
        server.start()
        val endpoint = server.url("/latest")
        server.shutdown()
        try { GitHubUpdates(endpoint = endpoint).latest(); fail("断网不能显示检查成功") }
        catch (e: UpdateFailure) { assertTrue(e.message.contains("无法连接 GitHub")) }
    }

    @Test fun nullBodyIsAnEmptyChangelogAndCancellationIsPreserved() = runBlocking {
        assertEquals("", parseRelease(JSONObject("""{"tag_name":"v1.2.3","body":null}""")).notes)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val job = async { GitHubUpdates(endpoint = server.url("/latest")).latest() }
            withContext(Dispatchers.IO) { server.takeRequest() }
            job.cancel()
            try { job.await(); fail("取消必须传播") } catch (_: CancellationException) { }
        }
    }
}
