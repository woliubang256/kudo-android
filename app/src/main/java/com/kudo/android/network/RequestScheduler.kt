package com.kudo.android.network

import okhttp3.HttpUrl
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class RequestLimits(val concurrent: Int = 3, val perWindow: Int = 6, val windowSeconds: Int = 3) {
    init {
        require(concurrent in 1..10 && perWindow in 2..30 && windowSeconds in 1..10)
    }
    companion object {
        val Gentle = RequestLimits(2, 6, 5)
        val Balanced = RequestLimits()
        val Turbo = RequestLimits(6, 15, 3)
    }
}

internal class RequestCoolingDown(val seconds: Int) : IOException("请求较频繁，请在 $seconds 秒后重试")

/** Shared by HTTP exchanges, not by authentication state or connection pools. */
class RequestScheduler(
    initial: RequestLimits = RequestLimits(),
    val cooldown: RequestCooldown = RequestCooldown(),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class OriginState {
        var running = 0
        val starts = ArrayDeque<Long>()
        val waiting = ArrayDeque<Any>()
    }
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var limits = initial
    private val origins = mutableMapOf<String, OriginState>()

    fun update(value: RequestLimits) = lock.withLock {
        limits = value
        changed.signalAll()
    }

    internal fun acquire(url: HttpUrl, cancelled: () -> Boolean): Closeable = lock.withLock {
        val now = nowMillis()
        origins.entries.removeAll { (_, state) ->
            state.running == 0 && state.waiting.isEmpty() && (state.starts.lastOrNull() ?: now) <= now - 10_000
        }
        val key = "${url.scheme}://${url.host}:${url.port}"
        val state = origins.getOrPut(key) { OriginState() }
        val ticket = Any()
        state.waiting.addLast(ticket)
        try {
            while (true) {
                if (cancelled()) throw IOException("Canceled")
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Request scheduling interrupted")
                cooldown.remainingSeconds(url).takeIf { it > 0 }?.let { throw RequestCoolingDown(it) }
                val current = nowMillis()
                // Retain the largest supported window, including when a smaller window is selected.
                while (state.starts.firstOrNull()?.let { it <= current - 10_000 } == true) state.starts.removeFirst()
                val window = state.starts.filter { it > current - limits.windowSeconds * 1000L }
                if (state.waiting.first() === ticket && state.running < limits.concurrent && window.size < limits.perWindow) {
                    state.waiting.removeFirst()
                    state.running++
                    state.starts.addLast(current)
                    changed.signalAll()
                    val released = AtomicBoolean()
                    return@withLock Closeable {
                        if (released.compareAndSet(false, true)) lock.withLock {
                            state.running--
                            changed.signalAll()
                        }
                    }
                }
                val delay = if (window.size >= limits.perWindow)
                    window[window.size - limits.perWindow] + limits.windowSeconds * 1000L - current else 100L
                changed.await(delay.coerceIn(1, 100), TimeUnit.MILLISECONDS)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Request scheduling interrupted").apply { initCause(e) }
        } finally {
            state.waiting.remove(ticket)
            changed.signalAll()
        }
    }
}
