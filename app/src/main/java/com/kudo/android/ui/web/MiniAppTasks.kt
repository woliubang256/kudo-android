package com.kudo.android.ui.web

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import com.kudo.android.MainActivity
import com.kudo.android.MiniAppActivity
import java.lang.ref.WeakReference

internal object MiniAppTasks {
    const val URL = "mini_app_url"
    const val ACCOUNT = "mini_app_account"
    const val SOURCE_TASK = "mini_app_source_task"
    private val activities = mutableMapOf<MiniAppTaskKey, WeakReference<MiniAppActivity>>()
    private var accountKnown = false
    var accountId: Int? = null
        private set
    var loginRequested by mutableStateOf(false)
        private set

    fun account(id: Int?) {
        if (accountKnown && accountId != id) activities.values.mapNotNull { it.get() }.toList().forEach { it.closeMiniApp() }
        accountKnown = true
        accountId = id
    }

    fun knownAccount(): Pair<Boolean, Int?> = accountKnown to accountId

    fun intent(context: Context, request: MiniAppTaskRequest): Intent = Intent(context, MiniAppActivity::class.java)
        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(request.key.identity))
        .putExtra(URL, request.url).putExtra(ACCOUNT, request.key.accountId ?: 0)
        .putExtra(SOURCE_TASK, context.activity()?.taskId ?: -1)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

    fun request(intent: Intent): MiniAppTaskRequest? {
        if (!intent.hasExtra(ACCOUNT)) return null
        val owner = intent.getIntExtra(ACCOUNT, -1)
        if (owner < 0) return null
        return intent.getStringExtra(URL)?.let { MiniAppTaskRequest.parse(it, owner.takeIf { it > 0 }) }
            ?.takeIf { it.key.identity == intent.dataString }
    }

    fun open(context: Context, url: String, owner: Int?): Boolean {
        val request = MiniAppTaskRequest.parse(url, owner) ?: return false
        val existing = activities[request.key]?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        if (existing != null) {
            existing.openMiniApp(request)
            if (foreground(context, existing.taskId)) return true
        }
        context.startActivity(intent(context, request))
        return true
    }

    fun attach(key: MiniAppTaskKey, activity: MiniAppActivity) { activities[key] = WeakReference(activity) }
    fun detach(key: MiniAppTaskKey, activity: MiniAppActivity) {
        if (activities[key]?.get() === activity) activities.remove(key)
    }

    fun main(context: Context, sourceTask: Int = -1, login: Boolean = false) {
        loginRequested = loginRequested || login
        if (sourceTask >= 0 && foreground(context, sourceTask)) return
        val manager = context.getSystemService(ActivityManager::class.java)
        val forum = manager.appTasks.firstOrNull { it.taskInfo.baseActivity?.className == MainActivity::class.java.name }
        if (forum != null) forum.moveToFront()
        else context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    fun consumeLogin() { loginRequested = false }
    private fun foreground(context: Context, taskId: Int): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull { it.taskInfo.taskId == taskId }
            ?.let { it.moveToFront(); true } ?: false
    }.getOrDefault(false)
}

internal fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
