package com.kudo.android.media

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.kudo.android.KudoApplication
import kotlinx.coroutines.*

/** Keeps user-started transfers alive while the app is in the background. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifier = DownloadNotifier(this)
        val downloads = (application as KudoApplication).container.downloads
        startForeground(NOTIFICATION_ID, notifier.progressNotification(downloads.tasks.value.firstOrNull {
            it.status == DownloadStatus.DOWNLOADING
        }, downloads.tasks.value.count { it.status == DownloadStatus.DOWNLOADING }))
        if (observation == null) observation = scope.launch {
            downloads.tasks.collect { tasks ->
                val active = tasks.filter { it.status == DownloadStatus.DOWNLOADING }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else startForeground(NOTIFICATION_ID, notifier.progressNotification(active.first(), active.size))
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as KudoApplication).container.downloads.tasks.value.filter { it.status == DownloadStatus.DOWNLOADING }
            .forEach { (application as KudoApplication).container.downloads.cancel(it.id) }
        stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object { private const val NOTIFICATION_ID = 0x4B55444F }
}
