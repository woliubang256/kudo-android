package com.kudo.android.ui

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface LinuxDoDestination {
    data class Topic(val id: Int, val number: Int? = null) : LinuxDoDestination
    data class User(val username: String) : LinuxDoDestination
    data class Category(val id: Int) : LinuxDoDestination
    data class Tag(val slug: String, val id: Int? = null) : LinuxDoDestination
    data object Home : LinuxDoDestination
    data object Unavailable : LinuxDoDestination
    data object Trust : LinuxDoDestination
    data object Apps : LinuxDoDestination
}

object LinuxDoLink {
    /** Normalizes article-relative URLs; CookieManager still decides each origin's cookies. */
    fun webUrl(value: String): String? = "https://linux.do/".toHttpUrlOrNull()?.resolve(value.trim())
        ?.takeIf { value.isNotBlank() && it.username.isEmpty() && it.password.isEmpty() }?.toString()

    /** Unknown site pages belong in the web reader, rather than the external-intent home fallback. */
    fun parseContent(value: String): LinuxDoDestination? {
        val url = webUrl(value)?.toHttpUrlOrNull() ?: return null
        if (url.host == "connect.linux.do" && url.port == if (url.isHttps) 443 else 80) return LinuxDoDestination.Trust
        val target = parse(url.toString()) ?: return null
        return target.takeUnless { it == LinuxDoDestination.Home && url.pathSegments.any(String::isNotEmpty) }
    }

    fun parse(value: String): LinuxDoDestination? {
        val url = value.trim().toHttpUrlOrNull() ?: return null
        if (url.host != "linux.do" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.port != if (url.isHttps) 443 else 80) return null
        val path = url.pathSegments.dropLastWhile { it.isEmpty() }
        return when (path.firstOrNull()) {
            "apps" -> if (path.size == 1) LinuxDoDestination.Apps else LinuxDoDestination.Home
            "404" -> if (path.size == 1) LinuxDoDestination.Unavailable else LinuxDoDestination.Home
            "t" -> {
                val offset = if (path.getOrNull(1)?.toIntOrNull() != null) 1 else 2
                val id = path.getOrNull(offset)?.toIntOrNull()?.takeIf { it > 0 }
                val floor = path.getOrNull(offset + 1)
                val number = floor?.toIntOrNull()?.takeIf { it > 0 }
                if (id == null || path.size > offset + 2 || (floor != null && number == null))
                    LinuxDoDestination.Home else LinuxDoDestination.Topic(id, number)
            }
            "u" -> path.getOrNull(1)?.takeIf {
                it.matches(Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]*"))
            }?.let(LinuxDoDestination::User) ?: LinuxDoDestination.Home
            "c" -> path.lastOrNull()?.toIntOrNull()?.takeIf { id ->
                id > 0 && path.size >= 2 && path.drop(1).dropLast(1).all { it.isNotBlank() }
            }?.let(LinuxDoDestination::Category) ?: LinuxDoDestination.Home
            "tag" -> {
                val slug = path.getOrNull(1)?.takeIf { it.matches(Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_-]*")) }
                val id = path.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
                if (slug == null || path.size > 3 || (path.size == 3 && id == null)) LinuxDoDestination.Home
                else LinuxDoDestination.Tag(slug, id)
            }
            else -> LinuxDoDestination.Home
        }
    }
}
