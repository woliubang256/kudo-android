package com.kudo.android

import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class MiniAppPreviewTest {
    private val id = "01a19ba5-b9d2-766f-9fb9-b7eddc10e0c7"
    private val entry = MiniAppEntry(id, "红包页", "https://cdn.example/card.svg")
    private fun body(appId: String = id, name: String = "红包页", icon: String = entry.iconUrl!!) =
        """{"app":{"app_id":"$appId","name":"$name","icon_url":"$icon"}}"""

    @Test fun previewUsesOnlyTheOfficialPublicInfoEndpointAndValidatesItsIdentity() = runTest {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val repository = CommunityRepository(DiscourseClient(NoCookies, server.url("/")))
            server.enqueue(MockResponse().setBody(body()))
            assertEquals(entry, repository.miniAppPreview(id))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/mini-app-api/public/$id/info", request.path)
            server.enqueue(MockResponse().setBody(body(appId = "unexpected")))
            assertTrue(runCatching { repository.miniAppPreview(id) }.isFailure)
            server.enqueue(MockResponse().setBody(body(name = " ")))
            assertTrue(runCatching { repository.miniAppPreview(id) }.isFailure)
            assertTrue(runCatching { repository.miniAppPreview("../business/claim") }.isFailure)
            assertEquals(3, server.requestCount)
            for (icon in listOf("javascript:bad()", "http://example.com/card.svg", "https://user:pass@example.com/card.svg", "null")) {
                server.enqueue(MockResponse().setBody(body(icon = icon)))
                assertNull(repository.miniAppPreview(id).iconUrl)
            }
        } finally { server.shutdown() }
    }

    @Test fun concurrentCardsShareMetadataAndOneCancelledCardDoesNotCancelTheOthers() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val cache = MiniAppPreviewCache(this, { calls++; release.await(); entry }, { testScheduler.currentTime })
        try {
            val first = async { cache.resolve(id) }
            val second = async { cache.resolve(id) }
            runCurrent()
            assertEquals(1, calls)
            first.cancelAndJoin()
            release.complete(Unit)
            assertEquals(entry, second.await())
            assertEquals(entry, cache.resolve(id))
            assertEquals(1, calls)
            advanceTimeBy(300_001)
            assertEquals(entry, cache.resolve(id))
            assertEquals(2, calls)
        } finally { cache.close() }
    }

    @Test fun failureBackoffExpiresAndTimeoutAndDisposalDoNotLeaveRequestsRunning() = runTest {
        var calls = 0
        var offline = true
        val cache = MiniAppPreviewCache(this, {
            calls++
            if (offline) error("offline") else entry
        }, { testScheduler.currentTime })
        try {
            assertNull(cache.resolve(id))
            offline = false
            assertNull(cache.resolve(id))
            assertEquals(1, calls)
            advanceTimeBy(15_001)
            assertEquals(entry, cache.resolve(id))
            assertEquals(2, calls)
        } finally { cache.close() }
        var cancelled = false
        val slow = MiniAppPreviewCache(this, { try { awaitCancellation() } finally { cancelled = true } })
        assertNull(slow.resolve(id))
        assertTrue(cancelled)
        val pending = async { slow.resolve("01a1966e-935a-79e0-8c76-9b0f7552c578") }
        runCurrent()
        slow.close()
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
    }
}
