package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.media.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in read-only check against the user's example, without logging cookies or changing network settings. */
class NativeDownloadLiveTest {
    @Test fun exampleTopicAttachmentUsesProductionNetworkRoute() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("downloadLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = (context.applicationContext as KudoApplication).container
        val prefs = context.getSharedPreferences("download-live-fixture", 0).apply { edit().clear().commit() }
        val fixture = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        val report = JSONObject().put("enabled", app.network.state.value.enabled)
            .put("effective", app.network.state.value.effective).put("vpn", app.network.state.value.vpn).put("proxy", app.network.state.value.proxy)
        var manager: NativeDownloadManager? = null
        var transport: com.kudo.android.network.DohTransport? = null
        try {
            val detail = withTimeout(60000) { app.repository.detail(3020259, 1) }
            val href = detail.posts.asSequence().flatMap { Jsoup.parseBodyFragment(it.cooked).select("a[href]").asSequence() }
                .map { it.attr("href") }.first { it.substringBefore('?').endsWith(".zip", true) }
            val url = com.kudo.android.ui.LinuxDoLink.webUrl(href)!!
            assertTrue(isDownloadUrl(url))
            val builder = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                chain.proceed(downloadRequest(chain.request(), app.cookies, app.userAgent)).also { response ->
                    report.put("lastHost", response.request.url.host).put("lastStatus", response.code)
                        .put("responseType", response.header("Content-Type").orEmpty())
                        .put("cloudflareChallenge", com.kudo.android.data.isCloudflareChallenge(response, response.peekBody(4096).string()))
                }
            }
            val direct = InstrumentationRegistry.getArguments().getString("downloadDirect") == "true"
            val calls = if (direct) {
                transport = com.kudo.android.network.DohTransport(app.network.settings.configuration.value.selected) { host, accepted ->
                    if (host == "linux.do") report.put("directEchAccepted", accepted)
                }
                report.put("directProbe", true)
                transport!!.configure(builder).build()
            } else app.network.client(builder.build())
            manager = NativeDownloadManager(fixture, calls, app.cookies, app.userAgent, notificationChannel = "download-live-fixture")
            val id = manager.enqueue(url)!!
            withTimeout(60000) { while (manager.tasks.value.single { it.id == id }.status == DownloadStatus.DOWNLOADING) delay(50) }
            val task = manager.tasks.value.single()
            report.put("status", task.status.name).put("bytes", task.bytes).put("name", task.fileName)
                .put("echAccepted", app.network.state.value.echAccepted)
            assertEquals(task.error, DownloadStatus.COMPLETED, task.status)
            val header = ByteArray(4)
            context.contentResolver.openInputStream(Uri.parse(task.fileUri))!!.use { assertEquals(4, it.read(header)) }
            assertEquals('P'.code, header[0].toInt()); assertEquals('K'.code, header[1].toInt())
            if (app.network.state.value.effective) assertTrue(app.network.state.value.echAccepted)
        } catch (failure: Exception) {
            report.put("failure", failure.javaClass.simpleName)
            throw failure
        } finally {
            File(context.getExternalFilesDir(null), "download-live-report.json").writeText(report.toString(2))
            manager?.tasks?.value?.forEach { task -> manager.cancel(task.id); task.fileUri?.let { context.contentResolver.delete(Uri.parse(it), null, null) } }
            prefs.edit().clear().commit()
            context.getSystemService(android.app.NotificationManager::class.java).deleteNotificationChannel("download-live-fixture")
            transport?.close()
        }
    }
}
