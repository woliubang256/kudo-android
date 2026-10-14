package com.kudo.android

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kudo.android.ui.KudoApp

class MainActivity : ComponentActivity() {
    private var incomingUrl by mutableStateOf<String?>(null)
    private var linkSequence by mutableStateOf(0L)
    private var incomingDownloads by mutableStateOf(false)
    private var incomingNotification by mutableStateOf<com.kudo.android.data.NotificationTarget?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = (application as KudoApplication).container
        incomingDownloads = intent?.action == com.kudo.android.media.DownloadNotifier.ACTION && savedInstanceState?.getBoolean("downloads_consumed") != true
        incomingUrl = intent?.dataString.takeUnless { savedInstanceState?.getBoolean("link_consumed") == true }
        incomingNotification = app.notifications.fromIntent(intent).takeUnless { savedInstanceState?.getBoolean("notification_consumed") == true }
        if (incomingNotification != null) incomingUrl = null
        setContent { KudoApp(app, incomingUrl, linkSequence, consumeLink = { incomingUrl = null },
            incomingNotification = incomingNotification, consumeNotification = { incomingNotification = null },
            incomingDownloads = incomingDownloads, consumeDownloads = { incomingDownloads = false }) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkSequence++
        incomingUrl = intent.dataString
        incomingDownloads = intent.action == com.kudo.android.media.DownloadNotifier.ACTION
        val app = (application as KudoApplication).container
        incomingNotification = app.notifications.fromIntent(intent)
        if (incomingNotification != null) incomingUrl = null
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("link_consumed", incomingUrl == null)
        outState.putBoolean("notification_consumed", incomingNotification == null)
        outState.putBoolean("downloads_consumed", !incomingDownloads)
        super.onSaveInstanceState(outState)
    }
}
