package com.kudo.android

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.SessionCookies
import com.kudo.android.media.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class NativeDownloadDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "download-device-fixture"
    private val fixture = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefsName, mode)
    }
    private val cookies = object : SessionCookies {
        override fun get(url: String) = ""
        override fun set(url: String, cookie: String) {}
    }
    private val server = MockWebServer()
    private lateinit var manager: NativeDownloadManager
    private val uris = mutableListOf<String>()
    private var failure: Exception? = null
    private val channel = "download-fixture-${System.nanoTime()}"

    @Before fun setup() { context.getSharedPreferences(prefsName, 0).edit().clear().commit(); server.start() }
    @After fun cleanup() {
        if (::manager.isInitialized) manager.tasks.value.forEach { task ->
            manager.cancel(task.id)
            task.fileUri?.let { uris.add(it) }
            context.getSystemService(android.app.NotificationManager::class.java).cancel(task.id.hashCode())
        }
        uris.distinct().forEach { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
        server.shutdown()
        context.getSharedPreferences(prefsName, 0).edit().clear().commit()
        context.getSystemService(android.app.NotificationManager::class.java).deleteNotificationChannel(channel)
    }

    private fun create(http: Call.Factory = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()) =
        NativeDownloadManager(fixture, http, cookies, "DownloadFixture/1", { failure = it }, notificationChannel = channel).also { manager = it }
    private fun awaitTerminal(id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (manager.tasks.value.first { it.id == id }.status == DownloadStatus.DOWNLOADING && System.nanoTime() < deadline) Thread.sleep(20)
        assertNotEquals(DownloadStatus.DOWNLOADING, manager.tasks.value.first { it.id == id }.status)
    }

    @Test fun htmlChallengeMustNotBecomeCompletedZip() {
        create()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><title>Just a moment...</title></html>"))
        val id = manager.enqueue(server.url("/fixture.zip").toString())!!
        awaitTerminal(id)
        assertEquals(DownloadStatus.FAILED, manager.tasks.value.single().status)
        assertNull(manager.tasks.value.single().fileUri)
    }

    @Test fun forbiddenCloudflareResponseShowsVerificationMessage() {
        create()
        server.enqueue(MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge")
            .setHeader("Content-Type", "text/html").setBody("<html>verify</html>"))
        val id = manager.enqueue(server.url("/fixture.zip").toString())!!
        awaitTerminal(id)
        assertEquals(DOWNLOAD_VERIFICATION_MESSAGE, manager.tasks.value.single().error)
        assertNull(manager.tasks.value.single().fileUri)
    }

    @Test fun removingCompletedRecordKeepsDownloadedFile() {
        create()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody("fixture bytes"))
        val id = manager.enqueue(server.url("/fixture.zip").toString())!!
        awaitTerminal(id)
        val task = manager.tasks.value.single()
        assertEquals(failure?.stackTraceToString(), DownloadStatus.COMPLETED, task.status)
        uris.add(task.fileUri!!)
        manager.remove(id)
        assertTrue(manager.tasks.value.isEmpty())
        assertEquals("fixture bytes", context.contentResolver.openInputStream(Uri.parse(task.fileUri))!!.bufferedReader().use { it.readText() })
    }

    @Test fun cancellationBeforeCallRegistrationCannotComplete() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        create(Call.Factory { request -> entered.countDown(); release.await(5, TimeUnit.SECONDS); http.newCall(request) })
        server.enqueue(MockResponse().setBody("fixture bytes"))
        val id = manager.enqueue(server.url("/fixture.zip").toString())!!
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        manager.cancel(id)
        release.countDown()
        Thread.sleep(800)
        assertEquals(DownloadStatus.CANCELED, manager.tasks.value.single().status)
        assertNull(manager.tasks.value.single().fileUri)
    }

    @Test fun missingNotificationChannelPermissionQueuesCompletionEvent() = runBlocking {
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        notifications.createNotificationChannel(android.app.NotificationChannel(channel, "下载测试", android.app.NotificationManager.IMPORTANCE_NONE))
        create()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setBody("fixture bytes"))
        val id = manager.enqueue(server.url("/fixture.zip").toString())!!
        awaitTerminal(id)
        val event = withTimeout(3000) { manager.events.first() }
        assertTrue(event is DownloadEvent.Completed)
        assertFalse(notifications.activeNotifications.any { it.id == id.hashCode() })
    }

    @Test fun failureCanRetryAndCompletionSurvivesManagerRecreation() {
        create()
        server.enqueue(MockResponse().setResponseCode(403))
        val id = manager.enqueue(server.url("/fixture.zip").toString())!!
        awaitTerminal(id)
        assertEquals(DownloadStatus.FAILED, manager.tasks.value.single().status)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/zip").setHeader("Content-Disposition", "attachment; filename*=UTF-8''test%20name.zip").setBody("complete bytes"))
        val retried = manager.retry(id)!!
        awaitTerminal(retried)
        assertEquals(DownloadStatus.COMPLETED, manager.tasks.value.single().status)
        assertEquals("test name.zip", manager.tasks.value.single().fileName)
        val task = manager.tasks.value.single()
        create()
        assertEquals(task, manager.tasks.value.single())
    }

    @Test fun systemNotificationUsesSafeLongProgressAndCompletion() {
        val notifier = DownloadNotifier(context, channel)
        val task = DownloadTask("progress-fixture", "https://linux.do/file.zip", "large.zip", DownloadStatus.DOWNLOADING,
            bytes = 3L * 1024 * 1024 * 1024, totalBytes = 6L * 1024 * 1024 * 1024)
        val progress = notifier.progressNotification(task, 1)
        assertEquals(50, progress.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        assertEquals(100, progress.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX))
        assertTrue(notifier.enabled())
        assertTrue(notifier.completed(task.copy(status = DownloadStatus.COMPLETED)))
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val notification = manager.activeNotifications.single { it.id == task.id.hashCode() }.notification
        assertEquals("下载完成", notification.extras.getString(android.app.Notification.EXTRA_TEXT))
        assertNotNull(notification.contentIntent)
        manager.cancel(task.id.hashCode())
    }
}
