package com.kudo.android.ui

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only Quark's published open_url link is accepted; arbitrary app commands are not. */
internal object ExternalAppLinkPolicy {
    const val QUARK_PACKAGE = "com.quark.browser"
    private const val QUARK_KEY = "b20b84fd735a8dd3f7541129bacc4e9a"

    fun quarkShare(value: String): String? = value.toHttpUrlOrNull()?.takeIf {
        it.host == "pan.quark.cn" && it.port == (if (it.isHttps) 443 else 80) &&
            it.username.isEmpty() && it.password.isEmpty() &&
            it.pathSegments.dropLastWhile(String::isEmpty).let { path ->
                path.size == 2 && path[0] == "s" && path[1].matches(Regex("[A-Za-z0-9_-]+"))
            }
    }?.toString()

    fun quarkScheme(value: String): String? {
        val link = value.trim()
        val scheme = link.substringBefore("://")
        if (scheme !in listOf("qklink", "uclink")) return null
        val parsed = ("https://" + link.substringAfter("://")).toHttpUrlOrNull() ?: return null
        if (parsed.host != "www.uc.cn" || parsed.port != 443 || parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty() || parsed.encodedPath != "/$QUARK_KEY" ||
            parsed.queryParameterValues("action") != listOf("open_url") ||
            parsed.queryParameterValues("url").size != 1) return null
        return link.takeIf { quarkShare(parsed.queryParameter("url").orEmpty()) != null }
    }

    fun targetPackage(value: String): String? = quarkShare(value)
        ?.takeIf { it.startsWith("https://") }
        ?.let { QUARK_PACKAGE }

    fun contentUrl(value: String): String? = LinuxDoLink.webUrl(value) ?: quarkScheme(value)

    /** Match domain labels, never linux.do.evil.test or notlinux.do. */
    fun isSiteUrl(value: String): Boolean = LinuxDoLink.webUrl(value)?.toHttpUrlOrNull()?.host?.let {
        it == "linux.do" || it.endsWith(".linux.do")
    } == true

    fun fallbackUrl(value: String): String? = LinuxDoLink.webUrl(value) ?: quarkScheme(value)?.let {
        ("https://" + it.substringAfter("://")).toHttpUrlOrNull()?.queryParameter("url")?.let(::quarkShare)
    }

    fun canOpenFromWeb(mainFrame: Boolean, gesture: Boolean, redirect: Boolean) = mainFrame && gesture && !redirect
}
