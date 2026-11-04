package com.kudo.android.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val previewAppId = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")

suspend fun CommunityRepository.miniAppPreview(id: String): MiniAppEntry {
    require(previewAppId.matches(id))
    val app = client.get("/mini-app-api/public/$id/info").getJSONObject("app")
    require(app.getString("app_id") == id)
    val name = app.getString("name").trim().also { require(it.isNotEmpty()) }
    val icon = app.optString("icon_url").toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
    return MiniAppEntry(id, name, icon)
}

/** Public app identity only; launch parameters and business state never enter the cache. */
internal class MiniAppPreviewCache(
    parentScope: CoroutineScope,
    private val load: suspend (String) -> MiniAppEntry,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private data class Cached(val value: MiniAppEntry?, val expires: Long)
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)
    private val pending = mutableMapOf<String, Deferred<MiniAppEntry?>>()

    suspend fun resolve(id: String): MiniAppEntry? {
        require(previewAppId.matches(id))
        job.ensureActive()
        return lock.withLock {
            val cached = cache[id]?.takeIf { it.expires > nowMillis() }
            if (cached != null) CompletableDeferred(cached.value)
            else pending[id] ?: scope.async(start = CoroutineStart.LAZY) {
                val value = try {
                    withTimeoutOrNull(5000) { load(id).also { require(it.id == id) } }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                lock.withLock {
                    job.ensureActive()
                    cache[id] = Cached(value, nowMillis() + if (value == null) 15_000 else 300_000)
                    while (cache.size > 64) cache.remove(cache.keys.first())
                    pending.remove(id)
                }
                value
            }.also { pending[id] = it; it.start() }
        }.await().also { job.ensureActive() }
    }

    override fun close() { job.cancel() }
}
