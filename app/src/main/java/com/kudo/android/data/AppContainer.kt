package com.kudo.android.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.kudo.android.media.NativeDownloadManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import kotlin.coroutines.resume

class AndroidSessionCookies : SessionCookies {
    private val manager get() = CookieManager.getInstance()
    override fun get(url: String): String = manager.getCookie(url).orEmpty()
    override fun set(url: String, cookie: String) { manager.setCookie(url, cookie); manager.flush() }
    suspend fun clear() = suspendCancellableCoroutine { continuation ->
        manager.removeAllCookies { manager.flush(); if (continuation.isActive) continuation.resume(Unit) }
    }
}

class AppContainer(context: Context) {
    val settings = AppSettings(context.applicationContext)
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
    internal val downloads = NativeDownloadManager(context.applicationContext, network.client(OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            chain.proceed(com.kudo.android.media.downloadRequest(request, cookies, userAgent))
        }.build()), cookies, userAgent, keepAlive = {
            context.startForegroundService(android.content.Intent(context, com.kudo.android.media.DownloadService::class.java))
        })
    val imageHttpClient: okhttp3.Call.Factory = scheduledClient(
        com.kudo.android.media.createImageHttpClient(client.cooldown, cookies, userAgent))
}
