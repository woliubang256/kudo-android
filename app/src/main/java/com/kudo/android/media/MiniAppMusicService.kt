package com.kudo.android.media

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.IBinder
import com.kudo.android.R
import com.kudo.android.ui.web.MiniAppMusic
import com.kudo.android.ui.web.MiniAppMusicController
import com.kudo.android.ui.web.MiniAppTasks
import kotlinx.coroutines.*
import java.util.UUID

/** Keeps the existing H5 audio process alive; this service never owns an audio source. */
class MiniAppMusicService : Service() {
    private var media: MediaSession? = null
    private var owner: MiniAppMusicController? = null
    private var token = ""
    private var foreground = false
    private var stopping = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkJob: Job? = null
    private var artworkData = ""
    private var artwork: Bitmap? = null
    private var metadataKey: List<Any?>? = null
    private var notificationKey: List<Any?>? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "小程序音乐", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!stopping) {
            if (intent?.getStringExtra("token") == token && token.isNotEmpty()) {
                val action = intent.action
                if (action in setOf("play", "pause", "previous", "next")) command(action!!)
            }
            refresh()
        }
        return START_NOT_STICKY
    }

    private fun command(action: String, positionMs: Long? = null) {
        val player = owner?.takeIf { it === MiniAppMusic.current } ?: return
        player.command(action, positionMs)
    }

    private fun refresh() {
        if (stopping) return
        val player = MiniAppMusic.current?.takeIf { it.state.canControl }
        if (player == null) { shutdown(); return }
        if (owner !== player) {
            releaseSession()
            // Merely loading a paused player must not create a system playback card.
            if (!player.state.playing) { shutdown(); return }
            owner = player
            token = UUID.randomUUID().toString()
            val session = MediaSession(this, "MiniAppMusic")
            media = session
            session.setCallback(object : MediaSession.Callback() {
                private fun send(action: String) {
                    if (media === session && owner === player && MiniAppMusic.current === player) command(action)
                }
                override fun onPlay() = send("play")
                override fun onPause() = send("pause")
                override fun onSkipToPrevious() = send("previous")
                override fun onSkipToNext() = send("next")
                override fun onSeekTo(pos: Long) {
                    if (media === session && owner === player && MiniAppMusic.current === player) command("seek", pos)
                }
            })
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        }
        val session = media ?: return
        val state = player.state
        if (artworkData != state.artwork) {
            artworkJob?.cancel()
            artworkData = state.artwork
            artwork = null
            if (artworkData.isNotEmpty()) {
                val data = artworkData
                artworkJob = scope.launch {
                    val image = withContext(Dispatchers.Default) { MiniAppMusicArtwork.decode(data) }
                    if (!stopping && owner === player && artworkData == data) { artwork = image; refresh() }
                }
            }
        }
        val open = MiniAppTasks.intent(this, player.request).putExtra(MiniAppTasks.SOURCE_TASK, -1)
        val activity = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session.setSessionActivity(activity)
        val info = listOf(state.title, state.artist, state.album, state.durationMs, artwork)
        if (metadataKey != info) {
            metadataKey = info
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title.ifBlank { "小程序音乐" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, state.album)
            if (state.durationMs > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
            artwork?.let { metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
            session.setMetadata(metadata.build())
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
        if (state.canSkip) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (state.canPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (state.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.positionMs.takeIf { it >= 0 } ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (state.playing) 1f else 0f).build())
        if (!session.isActive) session.isActive = true

        // Progress updates belong to PlaybackState. Avoid reposting the artwork
        // and foreground notification for every clock tick from the webpage.
        val notificationInfo = listOf(metadataKey, state.playing, state.canPrevious, state.canSkip)
        if (notificationKey == notificationInfo) return
        notificationKey = notificationInfo

        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_kudo)
            .setContentTitle(state.title.ifBlank { "小程序音乐" }).setContentText(state.artist)
            .setContentIntent(activity).setOngoing(state.playing).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
        artwork?.let { notification.setLargeIcon(it) }
        var count = 1
        if (state.canPrevious) { notification.addAction(action("previous", "上一首", android.R.drawable.ic_media_previous)); count++ }
        notification.addAction(if (state.playing) action("pause", "暂停", android.R.drawable.ic_media_pause)
            else action("play", "继续播放", android.R.drawable.ic_media_play))
        if (state.canSkip) { notification.addAction(action("next", "下一首", android.R.drawable.ic_media_next)); count++ }
        notification.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(*IntArray(count) { it }))
        if (state.playing) { startForeground(ID, notification.build()); foreground = true }
        else {
            if (foreground) { stopForeground(STOP_FOREGROUND_DETACH); foreground = false }
            notifications.notify(ID, notification.build())
        }
    }

    private fun action(name: String, label: String, icon: Int): Notification.Action {
        val intent = Intent(this, MiniAppMusicService::class.java).setAction(name)
            .setData(Uri.parse("kudo-media://$token/$name")).putExtra("token", token)
        return Notification.Action.Builder(icon, label, PendingIntent.getService(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    }

    private fun releaseSession() {
        artworkJob?.cancel(); artworkJob = null; artworkData = ""; artwork = null; metadataKey = null; notificationKey = null
        media?.apply { isActive = false; setCallback(null); release() }
        media = null; owner = null; token = ""
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        if (instance === this) instance = null
        releaseSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(ID)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseSession()
        scope.cancel()
        if (!stopping) { stopForeground(STOP_FOREGROUND_REMOVE); notifications.cancel(ID) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "mini_app_music"
        private const val ID = 0x4D555349
        private var instance: MiniAppMusicService? = null
        internal fun update(player: MiniAppMusicController) {
            val service = instance
            if (service != null) service.refresh()
            else if (player.state.playing) runCatching {
                player.context.startForegroundService(Intent(player.context, MiniAppMusicService::class.java))
            }
        }
        internal fun clear(context: Context) {
            val service = instance
            if (service != null) service.shutdown()
            else context.stopService(Intent(context, MiniAppMusicService::class.java))
        }
    }
}
