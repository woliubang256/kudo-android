package com.kudo.android

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.NotificationAlert
import com.kudo.android.data.NotificationTarget
import com.kudo.android.media.DownloadNotifier
import com.kudo.android.media.DownloadStatus
import com.kudo.android.media.DownloadTask
import com.kudo.android.notifications.LocalNotifications
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NotificationIconTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val fixture = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("notification_icon_fixture_$name", mode)
    }
    private val channel = "notification-icon-fixture"
    private val task = DownloadTask("notification-icon-fixture", "https://example.invalid/fixture.zip",
        "通知图标验证 · 下载", DownloadStatus.DOWNLOADING, bytes = 50, totalBytes = 100)

    @After fun cleanup() {
        LocalNotifications(fixture).account(null)
        manager.cancel(task.id.hashCode())
        manager.deleteNotificationChannel(channel)
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
    }

    @Test fun smallIconKeepsRecognizableKAndSeparateDotAtStatusBarSizes() {
        for (size in listOf(24, 48, 72)) {
            val bitmap = render(size)
            fun opaque(x: Float, y: Float) = Color.alpha(bitmap.getPixel(
                (x * size / 24).toInt(), (y * size / 24).toInt())) > 200
            // The stem, both arms and the detached dot must survive an alpha-only mask.
            for ((x, y) in listOf(4f to 5f, 4f to 12f, 4f to 19f,
                    12f to 5f, 13f to 18f, 18.5f to 18.5f)) {
                assertTrue("${size}px: K· 的笔画或圆点缺失 ($x, $y)", opaque(x, y))
            }
            for ((x, y) in listOf(12f to 2f, 1f to 12f, 12f to 22f,
                    23f to 12f, 12f to 11f, 15f to 15f)) {
                assertFalse("${size}px: K· 的留白被背景或旧图案填满 ($x, $y)", opaque(x, y))
            }
            assertEquals("${size}px: 单色渲染时 K 与圆点不能粘连", 2, components(bitmap))
            bitmap.recycle()
        }
    }

    @Test fun smallIconUsesOnlyWhiteForegroundAndTransparentBackground() {
        val bitmap = render(72)
        var foreground = 0
        var transparent = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) == 0) transparent++ else {
                assertEquals("通知图标前景必须能被系统安全着色", 0xffffff, pixel and 0xffffff)
                foreground++
            }
        }
        assertTrue("通知图标不能是实心色块", transparent > foreground)
        assertTrue("通知图标不能为空", foreground > 72 * 72 / 10)
        bitmap.recycle()
    }

    @Test fun communityAndAllDownloadNotificationsUseTheBrandSmallIcon() {
        val service = LocalNotifications(fixture)
        assertTrue("真机验收需要已开启通知权限", service.enabled())
        service.account(424242)
        service.show(NotificationAlert("icon-fixture", "通知图标验证 · 消息", "仅本机测试，不访问社区",
            NotificationTarget(424242)))
        val token = fixture.getSharedPreferences("runtime_notifications", 0).all.keys.single()
        val message = manager.activeNotifications.single { it.tag == "kudo.message.$token" }.notification
        assertEquals(R.drawable.ic_notification, message.smallIcon.resId)

        val notifier = DownloadNotifier(context, channel)
        val progress = notifier.progressNotification(task, 1)
        assertEquals(R.drawable.ic_notification, progress.smallIcon.resId)
        manager.notify(task.id.hashCode(), progress)
        capture("notification-icon-progress.png")
        assertTrue(notifier.completed(task.copy(status = DownloadStatus.COMPLETED)))
        assertEquals(R.drawable.ic_notification,
            manager.activeNotifications.single { it.id == task.id.hashCode() }.notification.smallIcon.resId)
        capture("notification-icon-completed.png")
        assertTrue(notifier.failed(task.copy(status = DownloadStatus.FAILED, error = "本机测试失败提示")))
        assertEquals(R.drawable.ic_notification,
            manager.activeNotifications.single { it.id == task.id.hashCode() }.notification.smallIcon.resId)
        capture("notification-icon-failed.png")
    }

    private fun render(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        context.getDrawable(R.drawable.ic_notification)!!.apply {
            setBounds(0, 0, size, size)
            draw(Canvas(it))
        }
    }

    private fun components(bitmap: Bitmap): Int {
        val remaining = mutableSetOf<Int>()
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 200) remaining.add(y * bitmap.width + x)
        }
        var count = 0
        while (remaining.isNotEmpty()) {
            count++
            val pending = ArrayDeque<Int>()
            pending.addLast(remaining.first().also { remaining.remove(it) })
            while (pending.isNotEmpty()) {
                val pixel = pending.removeFirst()
                val x = pixel % bitmap.width
                val y = pixel / bitmap.width
                for (dy in -1..1) for (dx in -1..1) {
                    if (x + dx in 0 until bitmap.width && y + dy in 0 until bitmap.height) {
                        val neighbor = (y + dy) * bitmap.width + x + dx
                        if (remaining.remove(neighbor)) pending.addLast(neighbor)
                    }
                }
            }
        }
        return count
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("notificationIconScreenshots") != "true") return
        instrumentation.uiAutomation.executeShellCommand("cmd statusbar expand-notifications").close()
        Thread.sleep(1000)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
