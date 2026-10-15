package com.kudo.android.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** One account's short-lived, optional avatar metadata. The owning composition closes it. */
class AvatarMetadataCache(
    parentScope: CoroutineScope,
    private val loadCards: suspend (List<Int>) -> List<Member>,
    private val loadEmojis: suspend () -> Map<String, String>,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val capacity: Int = 256,
    private val loadEmojiGroups: (suspend () -> List<SiteEmojiGroup>)? = null,
) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private data class Key(val id: Int, val username: String)
    private data class Cached(val member: Member?, val expires: Long)
    private data class Emojis(val groups: List<SiteEmojiGroup>, val expires: Long)
    private val lock = Mutex()
    private val cache = LinkedHashMap<Key, Cached>(16, 0.75f, true)
    private val pending = linkedMapOf<Key, CompletableDeferred<Member?>>()
    private var worker: Job? = null
    private var retryAfter = 0L
    private var nextCardRequestAt = 0L
    private val emojiLock = Mutex()
    private var emojis: Emojis? = null
    private var emojiRequest: Deferred<Emojis>? = null

    init { require(capacity > 0) }

    suspend fun resolve(member: Member): Member {
        job.ensureActive()
        if (member.avatarDetailsLoaded || member.flair != null || member.id <= 0 || member.username.isBlank()) return member
        val key = key(member)
        val result = lock.withLock {
            val cached = cache[key]?.takeIf { it.expires > nowMillis() }
            when {
                cached != null -> CompletableDeferred(cached.member)
                retryAfter > nowMillis() -> CompletableDeferred<Member?>().apply { complete(null) }
                else -> pending.getOrPut(key) { CompletableDeferred(job) }.also {
                    if (worker == null) worker = scope.launch { delay(250); drain() }
                }
            }
        }.await()
        job.ensureActive()
        return result?.let {
            member.copy(avatar = it.avatar.ifBlank { member.avatar }, animatedAvatar = it.animatedAvatar,
                flair = it.flair, status = it.status, avatarDetailsLoaded = true)
        } ?: member
    }

    private suspend fun drain() {
        while (currentCoroutineContext().isActive) {
            delay((nextCardRequestAt - nowMillis()).coerceAtLeast(0))
            val keys = lock.withLock {
                pending.keys.take(50).also { if (it.isEmpty()) worker = null }
            }
            if (keys.isEmpty()) return
            val backingOff = retryAfter > nowMillis()
            var failureDelay = RETRY_MILLIS
            val result = if (backingOff) null else {
                nextCardRequestAt = nowMillis() + 1000
                optionalRequest(onFailure = { failure ->
                    if (failure is ApiFailure && failure.kind == FailureKind.RATE_LIMIT)
                        failureDelay = maxOf(RETRY_MILLIS, failure.retryAfter?.toLong()?.times(1000) ?: RETRY_MILLIS)
                }) { loadCards(keys.map { it.id }.distinct()) }
            }
            val members = result?.associateBy(::key).orEmpty()
            lock.withLock {
                job.ensureActive()
                val now = nowMillis()
                if (result == null && !backingOff) retryAfter = now + failureDelay
                for (key in keys) {
                    val member = members[key]
                    cache[key] = Cached(member, if (result == null) retryAfter else now + CACHE_MILLIS)
                    pending.remove(key)?.complete(member)
                }
                while (cache.size > capacity) cache.remove(cache.keys.first())
            }
        }
    }

    suspend fun emojiUrl(name: String): String? {
        if (name.isBlank()) return null
        return emojiDirectory().flatMap { it.items }.firstOrNull { it.name == name }?.url ?: standardEmojiUrl(name)
    }

    suspend fun emojiDirectory(): List<SiteEmojiGroup> {
        job.ensureActive()
        val request = emojiLock.withLock {
            val cached = emojis?.takeIf { it.expires > nowMillis() }
            if (cached != null) CompletableDeferred(cached)
            else emojiRequest ?: scope.async(start = CoroutineStart.LAZY) {
                val result = optionalRequest { loadEmojiGroups?.invoke() ?: listOf(SiteEmojiGroup("custom", loadEmojis().map { (name, url) -> SiteEmoji(name, url, "custom") })) }
                val entry = Emojis(result.orEmpty(), if (result == null) nowMillis() + RETRY_MILLIS else Long.MAX_VALUE)
                emojiLock.withLock { emojis = entry; emojiRequest = null }
                entry
            }.also { emojiRequest = it; it.start() }
        }
        val groups = request.await().groups
        job.ensureActive()
        return groups
    }

    override fun close() { job.cancel() }

    private suspend fun <T> optionalRequest(onFailure: (Exception) -> Unit = {}, block: suspend () -> T): T? = try {
        withTimeoutOrNull(5000) { block() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { onFailure(e); null }

    private fun key(member: Member) = Key(member.id, member.username.lowercase(Locale.ROOT))

    companion object {
        private const val CACHE_MILLIS = 300_000L
        private const val RETRY_MILLIS = 15_000L
        fun standardEmojiUrl(name: String): String? = name.takeIf { it.matches(Regex("[A-Za-z0-9_+\\-]+(?::t[2-6])?")) }
            ?.let { "/images/emoji/twemoji/${it.replace(":t", "/")}.png?v=15" }
    }
}
