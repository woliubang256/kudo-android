package com.kudo.android

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.NotificationAlert
import com.kudo.android.data.NotificationTarget
import com.kudo.android.media.DownloadNotifier
import com.kudo.android.media.DownloadStatus
import com.kudo.android.media.DownloadTask
import com.kudo.android.notifications.LocalNotifications
import org.junit.After
import org.junit.Before
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

    @Before fun prepare() {
        val prefs = fixture.getSharedPreferences("runtime_notifications", 0)
        prefs.all.keys.forEach { manager.cancel("kudo.message.$it", 0) }
        prefs.edit().clear().commit()
    }

    @After fun cleanup() {
        LocalNotifications(fixture).account(null)
        fixture.getSharedPreferences("runtime_notifications", 0).edit().clear().commit()
        manager.cancel(task.id.hashCode())
        manager.deleteNotificationChannel(channel)
        shell("cmd statusbar collapse")
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
        val expected = R.mipmap.ic_notification_color
        val service = LocalNotifications(fixture)
        assertTrue("真机验收需要已开启通知权限", service.enabled())
        service.account(424242)
        service.show(NotificationAlert("icon-fixture", "通知图标验证 · 消息", "仅本机测试，不访问社区",
            NotificationTarget(424242)))
        val token = fixture.getSharedPreferences("runtime_notifications", 0).all.keys.single()
        val message = awaitNotification("消息通知") { it.tag == "kudo.message.$token" }
        assertEquals(expected, message.smallIcon.resId)
        captureStatus("notification-icon-message-status.png")
        capture("notification-icon-message.png")

        val notifier = DownloadNotifier(context, channel)
        val progress = notifier.progressNotification(task, 1)
        assertEquals(expected, progress.smallIcon.resId)
        manager.notify(task.id.hashCode(), progress)
        awaitNotification("下载进度通知") { it.id == task.id.hashCode() }
        capture("notification-icon-progress.png")
        assertTrue(notifier.completed(task.copy(status = DownloadStatus.COMPLETED)))
        assertEquals(expected,
            awaitNotification("下载完成通知") { it.id == task.id.hashCode() &&
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "下载完成" }.smallIcon.resId)
        capture("notification-icon-completed.png")
        assertTrue(notifier.failed(task.copy(status = DownloadStatus.FAILED, error = "本机测试失败提示")))
        assertEquals(expected,
            awaitNotification("下载失败通知") { it.id == task.id.hashCode() &&
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "本机测试失败提示" }.smallIcon.resId)
        capture("notification-icon-failed.png")
    }

    @Test fun communitySmallIconUsesAdaptiveResourceWithBrandColors() {
        val service = LocalNotifications(fixture)
        assertTrue(service.enabled())
        service.account(424242)
        service.show(NotificationAlert("color-fixture", "彩色图标验证", "仅本机测试",
            NotificationTarget(424242)))
        val token = fixture.getSharedPreferences("runtime_notifications", 0).all.keys.single()
        val message = awaitNotification("彩色消息通知") { it.tag == "kudo.message.$token" }
        val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
        val drawable = message.smallIcon.loadDrawable(context)!!
        assertTrue("所有厂商的消息通知都应使用自适应图标资源", drawable is AdaptiveIconDrawable)
        drawable.apply { setBounds(0, 0, 72, 72); draw(Canvas(bitmap)) }
        var yellow = 0
        for (y in 0 until 72) for (x in 0 until 72) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) > 200 && Color.red(pixel) > 200 && Color.green(pixel) > 100 && Color.blue(pixel) < 80) yellow++
        }
        bitmap.recycle()
        assertTrue("通知图标资源应包含品牌黄色，实际黄色像素=$yellow", yellow > 500)
    }

    private fun render(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        context.getDrawable(R.drawable.ic_notification)!!.apply {
            setBounds(0, 0, size, size)
            draw(Canvas(it))
        }
    }

    private fun awaitNotification(description: String, predicate: (StatusBarNotification) -> Boolean): Notification {
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            manager.activeNotifications.firstOrNull(predicate)?.let { return it.notification }
            Thread.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("5秒内未等到$description")
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
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        Thread.sleep(1500)
        saveScreenshot(name)
    }

    private fun captureStatus(name: String) {
        if (InstrumentationRegistry.getArguments().getString("notificationIconScreenshots") != "true") return
        shell("cmd statusbar collapse")
        Thread.sleep(1000)
        saveScreenshot(name)
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun saveScreenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
