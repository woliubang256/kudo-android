package com.kudo.android.notifications

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.kudo.android.MainActivity
import com.kudo.android.R
import com.kudo.android.data.*
import org.json.JSONObject
import java.util.UUID

/** Uses explicit, immutable Activity intents. Targets stay in private storage across cold starts. */
class LocalNotifications(context: Context) : NotificationDelivery {
    private val context = context.applicationContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.getSharedPreferences("runtime_notifications", Context.MODE_PRIVATE)
    private var accountId: Int? = null

    init { manager.createNotificationChannel(NotificationChannel(CHANNEL, "社区消息", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "应用运行期间收到的回复、回应、私信和聊天消息"
        setShowBadge(true)
    }) }

    fun enabled(): Boolean = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    override fun account(id: Int?) {
        accountId = id
        entries().forEach { (token, json) -> if (json.optInt("account") != id) cancel(token, json) }
    }

    override fun show(alert: NotificationAlert) {
        if (accountId != alert.target.accountId || !enabled()) return
        val key = "${alert.target.accountId}:${alert.key}"
        // Android limits the number of active notifications per app; retain the newest forty.
        val stored = entries()
        stored.filter { it.second.text("key") == key }.forEach { cancel(it.first, it.second) }
        stored.filter { it.second.text("key") != key }.sortedBy { it.second.optLong("time") }
            .take((stored.size - 39).coerceAtLeast(0)).forEach { cancel(it.first, it.second) }
        val token = UUID.randomUUID().toString()
        val target = alert.target.copy(token = token)
        val json = encode(target).put("key", key).put("username", alert.username).put("time", System.currentTimeMillis())
        prefs.edit().putString(token, json.toString()).commit()
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION)
            .setData(Uri.parse("kudo-notification://open/$token"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(TOKEN, token)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_notification_color)
            .setContentTitle(alert.title.take(200)).setContentText(alert.body.take(500))
            .setStyle(Notification.BigTextStyle().bigText(alert.body.take(2000)))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setColor(0xFFFFD43BL.toInt()).build()
        try { manager.notify(PREFIX + token, 0, notification) }
        catch (_: SecurityException) { prefs.edit().remove(token).apply() }
    }

    override fun correlate(notice: Notice) {
        entries().forEach { (token, json) ->
            val target = decode(json, token) ?: return@forEach
            val matches = target.noticeId == notice.id || (target.noticeId == null &&
                ((notice.chatChannelId != null && target.chatChannelId == notice.chatChannelId &&
                    (target.chatMessageId == null || target.chatMessageId == notice.chatMessageId)) ||
                    (notice.topicId != null && target.topicId == notice.topicId &&
                        (target.postNumber == null || target.postNumber == notice.postNumber))) &&
                (json.text("username").isBlank() || notice.actor?.username?.equals(json.text("username"), true) == true))
            if (matches) {
                json.put("notice", notice.id)
                prefs.edit().putString(token, json.toString()).apply()
                if (notice.read) cancel(token, json)
            }
        }
    }

    override fun readForum(ids: Set<Int>, all: Boolean) {
        entries().forEach { (token, json) ->
            if (json.optInt("account") == accountId &&
                (json.optInt("notice") in ids || (all && json.positiveInt("chat") == null))) cancel(token, json)
        }
    }
    override fun readChat(channelId: Int, messageId: Int) {
        entries().forEach { (token, json) ->
            if (json.optInt("account") == accountId && json.optInt("chat") == channelId &&
                (json.positiveInt("chat_message") ?: 0) <= messageId) cancel(token, json)
        }
    }
    fun fromIntent(intent: Intent?): NotificationTarget? {
        if (intent?.action != ACTION) return null
        val token = intent.getStringExtra(TOKEN)?.takeIf { it.length <= 64 } ?: return null
        return prefs.getString(token, null)?.let { runCatching { decode(JSONObject(it), token) }.getOrNull() }
    }
    fun consume(target: NotificationTarget) {
        manager.cancel(PREFIX + target.token, 0)
        // Keep the target until account/backend acknowledgement, including across rotation or a retry.
    }
    private fun entries(): List<Pair<String, JSONObject>> = prefs.all.mapNotNull { (key, value) ->
        (value as? String)?.let { runCatching { key to JSONObject(it) }.getOrNull() }
    }
    private fun cancel(token: String, json: JSONObject) {
        manager.cancel(PREFIX + token, 0)
        prefs.edit().remove(token).apply()
    }
    private fun encode(target: NotificationTarget) = JSONObject().put("account", target.accountId)
        .put("notice", target.noticeId).put("topic", target.topicId).put("post", target.postNumber)
        .put("chat", target.chatChannelId).put("chat_message", target.chatMessageId)
    private fun decode(json: JSONObject, token: String): NotificationTarget? {
        val id = json.positiveInt("account") ?: return null
        return NotificationTarget(id, json.positiveInt("notice"), json.positiveInt("topic"), json.positiveInt("post"),
            json.positiveInt("chat"), json.positiveInt("chat_message"), token)
    }
    companion object {
        const val CHANNEL = "community_messages"
        private const val PREFIX = "kudo.message."
        private const val ACTION = "com.kudo.android.OPEN_NOTIFICATION"
        private const val TOKEN = "notification_token"
    }
}
