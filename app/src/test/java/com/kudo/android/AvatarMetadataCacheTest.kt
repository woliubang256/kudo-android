package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AvatarMetadataCacheTest {
    private fun user(id: Int) = Member(id, "user$id", "用户$id", "/avatar/$id.png")
    private fun card(id: Int) = user(id).copy(status = UserStatus("coffee", "喝杯咖啡"), avatarDetailsLoaded = true)

    @Test fun revisitingAuthorsAfterOneMinuteReusesMetadataForFiveMinutes() = runTest {
        var requests = 0
        val cache = AvatarMetadataCache(this, { ids -> requests++; ids.map(::card) }, { emptyMap() }, { testScheduler.currentTime })
        try {
            cache.resolve(user(1))
            advanceTimeBy(120_000)
            cache.resolve(user(1))
            assertEquals("来回浏览不能每分钟重复补齐作者", 1, requests)
            advanceTimeBy(180_001)
            cache.resolve(user(1))
            assertEquals(2, requests)
        } finally { cache.close() }
    }

    @Test fun largeVisibleBatchesHaveAMinimumIntervalInsteadOfDrainingInABurst() = runTest {
        val timestamps = mutableListOf<Long>()
        val cache = AvatarMetadataCache(this, { ids -> timestamps += testScheduler.currentTime; ids.map(::card) }, { emptyMap() }, { testScheduler.currentTime })
        try {
            (1..61).map { async { cache.resolve(user(it)) } }.awaitAll()
            assertEquals(2, timestamps.size)
            assertTrue("连续补齐批次至少间隔一秒", timestamps[1] - timestamps[0] >= 1000)
        } finally { cache.close() }
    }

    @Test fun rateLimitBackoffRespectsTheServerWaitInsteadOfRetryingAfterFifteenSeconds() = runTest {
        var requests = 0
        val cache = AvatarMetadataCache(this, {
            requests++
            throw ApiFailure(FailureKind.RATE_LIMIT, "稍后再试", 60)
        }, { emptyMap() }, { testScheduler.currentTime })
        try {
            cache.resolve(user(1))
            advanceTimeBy(20_000)
            cache.resolve(user(2))
            assertEquals(1, requests)
            advanceTimeBy(40_001)
            cache.resolve(user(2))
            assertEquals(2, requests)
        } finally { cache.close() }
    }

    @Test fun visibleRequestsMergeDeduplicateAndRespectServerBatchLimit() = runTest {
        val batches = mutableListOf<List<Int>>()
        val cache = AvatarMetadataCache(this, { ids -> batches += ids; ids.map(::card) }, { emptyMap() }, { testScheduler.currentTime })
        try {
            val requests = (1..61).map { async { cache.resolve(user(it)) } } + async { cache.resolve(user(1)) }
            requests.awaitAll().forEach { assertEquals("coffee", it.status?.emoji) }
            assertEquals(listOf(50, 11), batches.map { it.size })
            assertEquals(61, batches.flatten().distinct().size)
            assertEquals("coffee", cache.resolve(user(1)).status!!.emoji)
            assertEquals(2, batches.size)
        } finally { cache.close() }
    }

    @Test fun completeResponsesAndFlairDoNotRestoreAnOldStatusOrRequestCards() = runTest {
        var requests = 0
        val cache = AvatarMetadataCache(this, { ids -> requests++; ids.map(::card) }, { emptyMap() })
        try {
            assertNotNull(cache.resolve(user(7)).status)
            assertNull(cache.resolve(user(7).copy(avatarDetailsLoaded = true)).status)
            val withFlair = user(8).copy(flair = AvatarFlair("staff", "battery-quarter"))
            assertEquals(withFlair, cache.resolve(withFlair))
            assertEquals(user(0), cache.resolve(user(0)))
            assertEquals(1, requests)
        } finally { cache.close() }
    }

    @Test fun cacheExpiresAndEvictsLeastRecentlyUsedEntries() = runTest {
        val requests = mutableListOf<Int>()
        val cache = AvatarMetadataCache(this, { ids -> requests += ids; ids.map(::card) }, { emptyMap() }, { testScheduler.currentTime }, capacity = 2)
        try {
            cache.resolve(user(1)); cache.resolve(user(2)); cache.resolve(user(1)); cache.resolve(user(3))
            cache.resolve(user(2))
            assertEquals(listOf(1, 2, 3, 2), requests)
            advanceTimeBy(300_001)
            cache.resolve(user(2))
            assertEquals(listOf(1, 2, 3, 2, 2), requests)
        } finally { cache.close() }
    }

    @Test fun optionalFailuresBackOffAndWrongIdentitiesCannotSupplyBadges() = runTest {
        var requests = 0
        val cache = AvatarMetadataCache(this, { ids ->
            requests++
            if (requests == 1) error("验证暂不可用")
            ids.map { card(it).copy(username = "unrelated") }
        }, { emptyMap() }, { testScheduler.currentTime })
        try {
            (1..20).map { async { cache.resolve(user(it)) } }.awaitAll().forEach { assertNull(it.status) }
            assertEquals(1, requests)
            assertNull(withTimeout(1000) { cache.resolve(user(21)) }.status)
            assertEquals(1, requests)
            advanceTimeBy(15_001)
            assertNull(cache.resolve(user(1)).status)
            assertEquals(2, requests)
        } finally { cache.close() }
    }

    @Test fun closingAccountCacheCancelsInFlightRequestsAndNewAccountStartsEmpty() = runTest {
        var cancelled = false
        val cache = AvatarMetadataCache(this, {
            try { awaitCancellation() } finally { cancelled = true }
        }, { emptyMap() })
        val request = async { cache.resolve(user(1)) }
        advanceTimeBy(251); runCurrent()
        cache.close()
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
        runCurrent()
        assertTrue(cancelled)
        val replacement = AvatarMetadataCache(this, { ids -> ids.map { user(it).copy(avatarDetailsLoaded = true) } }, { emptyMap() })
        try { assertNull(replacement.resolve(user(1)).status) } finally { replacement.close() }
    }

    @Test fun emojiDirectoryLoadsOnceAndRetainsCustomUrlsAndSafeStandardAliases() = runTest {
        var requests = 0
        val cache = AvatarMetadataCache(this, { emptyList() }, {
            requests++; delay(50); mapOf("bili_118" to "//cdn.example/custom.png")
        })
        try {
            val custom = async { cache.emojiUrl("bili_118") }
            val standard = async { cache.emojiUrl("no_entry_sign") }
            assertEquals("//cdn.example/custom.png", custom.await())
            assertEquals("/images/emoji/twemoji/no_entry_sign.png?v=15", standard.await())
            assertNull(cache.emojiUrl("../../elsewhere"))
            assertEquals(1, requests)
        } finally { cache.close() }
    }

    @Test fun repositoryUsesPublicBatchAndEmojiEndpointsAndMarksKnownEmptyStatus() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val repo = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            server.enqueue(MockResponse().setBody("""{"users":[{"id":7,"username":"user7"}]}"""))
            val result = repo.userCards(listOf(7, 7, 0, -1))
            assertEquals("7", server.takeRequest().requestUrl!!.queryParameter("user_ids"))
            assertTrue(result.single().avatarDetailsLoaded)
            assertNull(result.single().status)
            assertTrue(repo.userCards(emptyList()).isEmpty())
            assertTrue(runCatching { repo.userCards((1..51).toList()) }.isFailure)
            server.enqueue(MockResponse().setBody("""{"custom":[{"name":"custom","url":"//cdn.example/emoji.gif"}]}"""))
            assertEquals("//cdn.example/emoji.gif", repo.emojiUrls()["custom"])
            assertEquals("/emojis.json", server.takeRequest().path)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
}
