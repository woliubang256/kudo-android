package com.kudo.android.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.kudo.android.media.NativeDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private val androidSessionCookies by lazy {
    SessionCookieStore(object : CookieStorage {
        private val manager get() = CookieManager.getInstance()
        override fun get(url: String): String = manager.getCookie(url).orEmpty()
        // With no callback, Chromium completes these operations synchronously on its cookie thread.
        override fun set(url: String, cookie: String) { manager.setCookie(url, cookie) }
        override fun clear() { manager.removeAllCookies(null) }
        override fun flush() { manager.flush() }
    })
}

class AndroidSessionCookies : SessionCookies by androidSessionCookies {
    suspend fun clear() = withContext(Dispatchers.IO) { androidSessionCookies.clear() }
}

class AppContainer(context: Context) {
    val settings = AppSettings(context.applicationContext)
    val homeTabs = HomeTabsStore(context.applicationContext)
    val imageLibrary = ImageLibraryStore(context.applicationContext)
    val miniApps = MiniAppPreferences(context.applicationContext)
    val network = com.kudo.android.network.DohNetwork(context.applicationContext, DohSettings(context.applicationContext))
    val requestSettings = RequestSettings(context.applicationContext)
    val notifications = com.kudo.android.notifications.LocalNotifications(context.applicationContext)
    val cookies = AndroidSessionCookies()
    val userAgent: String = WebSettings.getDefaultUserAgent(context.applicationContext)
    private fun scheduledClient(base: OkHttpClient): okhttp3.Call.Factory = network.client(base.newBuilder()
        .addNetworkInterceptor(com.kudo.android.network.RequestSchedulingInterceptor(requestSettings.scheduler)).build())
    val client = DiscourseClient(cookies, userAgent = { userAgent }, route = ::scheduledClient,
        cooldown = requestSettings.scheduler.cooldown)
    val repository = CommunityRepository(client)
    val connect = ConnectRepository(cookies, userAgent, route = ::scheduledClient)
    internal val downloads = NativeDownloadManager(context.applicationContext,
        network.client(com.kudo.android.media.createDownloadHttpClient(cookies, userAgent)), cookies, userAgent, keepAlive = {
            context.startForegroundService(android.content.Intent(context, com.kudo.android.media.DownloadService::class.java))
        })
    val imageHttpClient: okhttp3.Call.Factory = scheduledClient(
        com.kudo.android.media.createImageHttpClient(client.cooldown, cookies, userAgent))
}
