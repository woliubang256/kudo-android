package com.kudo.android.data

/** Request credentials stay in memory and must never appear in diagnostic output. */
class SessionCookieSnapshot internal constructor(internal val header: String, internal val generation: Long) {
    override fun toString() = "SessionCookieSnapshot(redacted)"
}

interface SessionCookies {
    fun get(url: String): String
    fun set(url: String, cookie: String)
    fun snapshot(url: String): SessionCookieSnapshot = SessionCookieSnapshot(get(url), 0)
    fun isCurrent(snapshot: SessionCookieSnapshot): Boolean = true
    fun invalidate() {}
    fun receive(url: String, headers: List<String>, snapshot: SessionCookieSnapshot): Boolean = synchronized(this) {
        if (!isCurrent(snapshot)) return@synchronized false
        if (headers.isEmpty()) return@synchronized true
        sessionResponseCookies(headers, snapshot.header, get(url)).forEach { set(url, it) }
        true
    }
}

private fun authToken(header: String): String? = header.split(';').firstNotNullOfOrNull { part ->
    val pair = part.trim()
    if (pair.substringBefore('=') == "_t" && '=' in pair) pair.substringAfter('=') else null
}

internal fun SessionCookies.hasUpdatedAuth(url: String, previous: SessionCookieSnapshot): Boolean {
    val current = snapshot(url)
    return isCurrent(previous) && current.generation == previous.generation &&
        authToken(current.header).let { !it.isNullOrBlank() && it != authToken(previous.header) }
}

private fun sessionResponseCookies(headers: List<String>, sent: String, current: String): List<String> {
    if (authToken(sent) == authToken(current)) return headers
    return headers.filterNot { it.substringBefore('=').trim() in setOf("_t", "_forum_session") }
}

internal interface CookieStorage {
    fun get(url: String): String
    fun set(url: String, cookie: String)
    fun flush()
    fun clear()
}

/** Serializes native mutations; browser writes are detected by comparing the live auth token. */
internal class SessionCookieStore(private val storage: CookieStorage) : SessionCookies {
    private val lock = Any()
    private var generation = 0L

    override fun get(url: String): String = synchronized(lock) { storage.get(url) }
    override fun set(url: String, cookie: String) = synchronized(lock) {
        storage.set(url, cookie)
        storage.flush()
    }
    override fun snapshot(url: String): SessionCookieSnapshot = synchronized(lock) {
        SessionCookieSnapshot(storage.get(url), generation)
    }
    override fun isCurrent(snapshot: SessionCookieSnapshot): Boolean = synchronized(lock) { snapshot.generation == generation }
    override fun invalidate() = synchronized(lock) { generation++; Unit }
    override fun receive(url: String, headers: List<String>, snapshot: SessionCookieSnapshot): Boolean = synchronized(lock) {
        if (snapshot.generation != generation) return@synchronized false
        if (headers.isEmpty()) return@synchronized true
        val accepted = sessionResponseCookies(headers, snapshot.header, storage.get(url))
        accepted.forEach { storage.set(url, it) }
        if (accepted.isNotEmpty()) storage.flush()
        true
    }
    fun clear() = synchronized(lock) {
        generation++
        storage.clear()
        storage.flush()
    }
}
