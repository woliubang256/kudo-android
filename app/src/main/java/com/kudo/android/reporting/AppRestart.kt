package com.kudo.android.reporting

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.kudo.android.MainActivity
import java.io.File

internal fun currentProcessName(context: Context): String = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
    else context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
        .firstOrNull { it.pid == Process.myPid() }?.processName
        ?: File("/proc/self/cmdline").readText(Charsets.UTF_8).substringBefore('\u0000')

/** A foreground helper survives the main process, then launches a clean process without clearing data. */
internal object AppRestart {
    const val PARENT_PID = "restart_parent_pid"
    const val PARENT_PROCESS = "restart_parent_process"
    const val TARGET = "restart_target"
    fun request(context: Context, target: ComponentName = ComponentName(context, MainActivity::class.java)): Boolean = runCatching {
        require(target.packageName == context.packageName)
        context.startActivity(Intent(context, ReportingRestartActivity::class.java)
            .putExtra(PARENT_PID, Process.myPid()).putExtra(PARENT_PROCESS, currentProcessName(context))
            .putExtra(TARGET, target.className)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        true
    }.getOrDefault(false)
}

class ReportingRestartActivity : Activity() {
    private var requested = false
    override fun onResume() {
        super.onResume()
        if (requested) return
        requested = true
        val pid = intent.getIntExtra(AppRestart.PARENT_PID, -1)
        val process = intent.getStringExtra(AppRestart.PARENT_PROCESS)
        val target = intent.getStringExtra(AppRestart.TARGET)
        val manager = getSystemService(ActivityManager::class.java)
        val parent = manager.runningAppProcesses.orEmpty().singleOrNull { it.pid == pid }
        if (pid <= 0 || pid == Process.myPid() || parent?.uid != Process.myUid() || parent.processName != process ||
            (process != packageName && !process.startsWith("$packageName:")) || target == null) {
            finishAndRemoveTask(); return
        }
        val component = ComponentName(packageName, target)
        if (runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull() == null) {
            finishAndRemoveTask(); return
        }
        // Wait until the helper is foreground before terminating the parent (background-launch rules).
        Handler(Looper.getMainLooper()).postDelayed({
            manager.appTasks.forEach { task -> runCatching {
                val info = task.taskInfo
                @Suppress("DEPRECATION") val id = if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id
                if (id != taskId && (process == packageName || info.baseActivity == component)) task.finishAndRemoveTask()
            } }
            Process.killProcess(pid)
            Thread({
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (manager.runningAppProcesses.orEmpty().any { it.pid == pid } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
                runOnUiThread {
                    runCatching { startActivity(Intent.makeRestartActivityTask(component)) }
                    finishAndRemoveTask()
                    // Do not retain an idle helper process in the background.
                    Process.killProcess(Process.myPid())
                }
            }, "app-restart").start()
        }, 100)
    }
}
