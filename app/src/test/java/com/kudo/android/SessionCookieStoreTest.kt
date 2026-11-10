package com.kudo.android

import com.kudo.android.data.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionCookieStoreTest {
    private val url = "https://linux.do/session/current.json"
    private class Storage(private val disk: MutableMap<String, Cookie> = linkedMapOf()) : CookieStorage {
        private val memory = disk.toMutableMap()
        val writes = mutableListOf<String>()
        var flushes = 0
        var beforeFlush: () -> Unit = {}
        override fun get(url: String) = memory.values.filter { it.matches(url.toHttpUrl()) && it.expiresAt > System.currentTimeMillis() }
            .joinToString("; ") { "${it.name}=${it.value}" }
        override fun set(url: String, cookie: String) {
            writes += cookie
            Cookie.parse(url.toHttpUrl(), cookie)?.let { memory[it.name] = it }
        }
        override fun flush() {
            beforeFlush()
            flushes++
            disk.clear()
            disk.putAll(memory.filterValues { it.persistent && it.expiresAt > System.currentTimeMillis() })
        }
        override fun clear() { memory.clear() }
    }

    @Test fun rawAttributesArePreservedAndTheBatchIsFlushedBeforeReturning() {
        val disk = linkedMapOf<String, Cookie>()
        val storage = Storage(disk)
        val store = SessionCookieStore(storage)
        val headers = listOf("_t=persistent; Path=/; Secure; HttpOnly; Max-Age=3600; SameSite=Lax",
            "_forum_session=browser-session; Path=/; Secure; HttpOnly")
        assertTrue(store.receive(url, headers, store.snapshot(url)))
        assertEquals(headers, storage.writes)
        assertEquals(1, storage.flushes)
        val reopened = SessionCookieStore(Storage(disk))
        assertEquals("_t=persistent", reopened.get(url))
        assertTrue(store.get(url).contains("_forum_session=browser-session"))
    }

    @Test fun clearAndNewLoginRejectEvenAnIdenticalOldToken() {
        val store = SessionCookieStore(Storage())
        store.set(url, "_t=same; Path=/; Secure; Max-Age=3600")
        val old = store.snapshot(url)
        store.clear()
        store.set(url, "_t=same; Path=/; Secure; Max-Age=3600")
        assertFalse(store.receive(url, listOf("_t=stale; Path=/; Secure"), old))
        assertEquals("_t=same", store.get(url))
        val beforeBrowser = store.snapshot(url)
        store.invalidate()
        assertFalse(store.isCurrent(beforeBrowser))
        assertFalse(store.receive(url, listOf("cf_clearance=stale; Path=/"), beforeBrowser))
    }

    @Test fun obsoleteRotationOrDeletionCannotReplaceTheLiveSession() {
        val store = SessionCookieStore(Storage())
        store.set(url, "_t=old; Path=/; Secure")
        val old = store.snapshot(url)
        store.receive(url, listOf("_t=new; Path=/; Secure", "_forum_session=fresh; Path=/; Secure"), old)
        store.receive(url, listOf("_t=; Path=/; Max-Age=0", "_forum_session=stale; Path=/; Secure",
            "cf_clearance=fresh; Path=/; Secure"), old)
        assertTrue(store.get(url).contains("_t=new"))
        assertTrue(store.get(url).contains("_forum_session=fresh"))
        assertTrue(store.get(url).contains("cf_clearance=fresh"))
        store.receive(url, listOf("_t=; Path=/; Max-Age=0"), store.snapshot(url))
        assertFalse(store.get(url).contains("_t="))
    }

    @Test fun concurrentResponsesCannotInterleaveAnUnflushedRenewalWithAnOldDeletion() {
        val storage = Storage()
        val store = SessionCookieStore(storage)
        store.set(url, "_t=old; Path=/; Secure")
        val old = store.snapshot(url)
        val flushing = CountDownLatch(1)
        val release = CountDownLatch(1)
        storage.beforeFlush = { flushing.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val renewal = workers.submit<Boolean> { store.receive(url, listOf("_t=new; Path=/; Secure"), old) }
            assertTrue(flushing.await(3, TimeUnit.SECONDS))
            val deletion = workers.submit<Boolean> { store.receive(url, listOf("_t=; Path=/; Max-Age=0"), old) }
            assertFalse(deletion.isDone)
            release.countDown()
            assertTrue(renewal.get(3, TimeUnit.SECONDS))
            assertTrue(deletion.get(3, TimeUnit.SECONDS))
            assertEquals("_t=new", store.get(url))
            assertEquals(2, storage.flushes)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun snapshotDiagnosticsNeverPrintCredentials() {
        val store = SessionCookieStore(Storage())
        store.set(url, "_t=private; Path=/; Secure")
        assertFalse(store.snapshot(url).toString().contains("private"))
    }
}
