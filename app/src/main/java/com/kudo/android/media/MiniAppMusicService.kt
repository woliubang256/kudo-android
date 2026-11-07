package com.kudo.android.media

import android.app.*
import android.content.Intent
import android.os.IBinder
import com.kudo.android.R
import com.kudo.android.ui.web.MiniAppMusic
import com.kudo.android.ui.web.MiniAppTasks

/** Keeps the existing H5 audio process alive; this service never owns an audio source. */
class MiniAppMusicService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = MiniAppMusic.current
        if (intent?.action == "pause" && intent.getStringExtra("session") == session?.key?.identity) session?.command("pause")
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "小程序音乐", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_kudo)
            .setContentTitle(session?.state?.title?.ifBlank { "小程序音乐" } ?: "小程序音乐")
            .setContentText(session?.state?.artist).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
        if (session != null) {
            val open = MiniAppTasks.intent(this, session.request).putExtra(MiniAppTasks.SOURCE_TASK, -1)
            notification.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val pause = Intent(this, MiniAppMusicService::class.java).setAction("pause").putExtra("session", session.key.identity)
            notification.addAction(Notification.Action.Builder(null, "暂停", PendingIntent.getService(this, 0, pause,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        startForeground(ID, notification.build())
        if (session?.state?.playing != true) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "mini_app_music"; private const val ID = 0x4D555349 }
}
