package com.kudo.android

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kudo.android.data.ApiFailure
import com.kudo.android.data.FailureKind
import com.kudo.android.ui.ExternalAppLinkLauncher
import com.kudo.android.ui.components.CommunityButton
import com.kudo.android.ui.components.CommunityLoadingIndicator
import com.kudo.android.ui.theme.KudoTheme
import com.kudo.android.ui.web.*
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.*

class MiniAppActivity : ComponentActivity() {
    private var request by mutableStateOf<MiniAppTaskRequest?>(null)
    private var ready by mutableStateOf(false)
    private var failure by mutableStateOf<String?>(null)
    private var validation: Job? = null
    private var sourceTask = -1
    private var info: MiniAppInfo? = null
    private var minimizing = false
    private val app get() = (application as KudoApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = MiniAppTasks.request(intent) ?: run { finishAndRemoveTask(); return }
        request = initial
        sourceTask = intent.getIntExtra(MiniAppTasks.SOURCE_TASK, -1)
        MiniAppTasks.attach(initial.key, this)
        setTaskDescription(ActivityManager.TaskDescription("小程序"))
        setContent {
            val display by app.settings.display.collectAsStateWithLifecycle()
            KudoTheme(display) {
                MiniAppFloatingHost(request?.key?.accountId, ready, pageKey = request?.url) {
                if (ready) request?.let { current ->
                    key(current.url) {
                        WebScreen(current.url, ::closeMiniApp, ::openBrowser,
                            openApp = { ExternalAppLinkLauncher(this).open(it, display.externalLinkMode) },
                            externalLinkMode = display.externalLinkMode, preferences = app.miniApps,
                            accountId = current.key.accountId, accountReady = true,
                            login = { MiniAppTasks.main(this, sourceTask, login = true) }, infoChanged = ::updateInfo,
                            minimize = ::minimize, pinShortcut = ::pinShortcut, musicRequest = current)
                    }
                } else Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    if (failure == null) CommunityLoadingIndicator()
                    else { Text(failure!!); CommunityButton("重试", ::validateAccount) }
                }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); request?.key?.let(MiniAppTasks::resumed); validateAccount() }

    private fun minimize(view: WebView) {
        val current = request ?: return
        if (minimizing || !view.isAttachedToWindow) return
        minimizing = true
        val position = IntArray(2).also(view::getLocationInWindow)
        val rect = Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
        fun complete(preview: Bitmap?) {
            minimizing = false
            if (isFinishing || isDestroyed || request != current || !hasWindowFocus()) return
            MiniAppTasks.minimize(MiniAppFloatingEntry(current, taskId, info, preview))
            moveTaskToBack(true)
            MiniAppTasks.main(this, sourceTask)
        }
        if (rect.width() <= 0 || rect.height() <= 0) { complete(null); return }
        val width = minOf(320, rect.width())
        val bitmap = Bitmap.createBitmap(width, (rect.height().toFloat() / rect.width() * width).toInt().coerceIn(1, 1600), Bitmap.Config.ARGB_8888)
        runCatching { PixelCopy.request(window, rect, bitmap, { result ->
            complete(bitmap.takeIf { result == PixelCopy.SUCCESS })
        }, Handler(Looper.getMainLooper())) }.onFailure { complete(null) }
    }

    private fun validateAccount() {
        val current = request ?: return
        validation?.cancel()
        failure = null
        validation = lifecycleScope.launch {
            try {
                val owner = try { withContext(Dispatchers.IO) { app.repository.currentUser()?.id } }
                catch (error: ApiFailure) { if (error.kind == FailureKind.SIGN_IN) null else throw error }
                MiniAppTasks.account(owner)
                if (owner != current.key.accountId) {
                    android.widget.Toast.makeText(this@MiniAppActivity, "账号已变化，请重新打开小程序", android.widget.Toast.LENGTH_SHORT).show()
                    closeMiniApp()
                } else ready = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!ready) failure = "暂时无法确认登录状态" }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val next = MiniAppTasks.request(intent) ?: return
        if (next.key != request?.key) return
        setIntent(intent)
        openMiniApp(next)
    }

    internal fun openMiniApp(next: MiniAppTaskRequest) {
        if (next.key == request?.key && next.url != request?.url) request = next
    }

    internal fun closeMiniApp() {
        request?.key?.let(MiniAppMusic::close)
        finishAndRemoveTask()
    }

    private fun pinShortcut() {
        val current = request ?: return
        lifecycleScope.launch {
            val accepted = runCatching { MiniAppShortcuts.pin(this@MiniAppActivity, current, info) }.getOrDefault(false)
            if (!accepted && !isFinishing) android.widget.Toast.makeText(this@MiniAppActivity, "当前桌面无法添加快捷方式", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateInfo(info: MiniAppInfo) {
        if (info.id != request?.key?.appId) return
        this.info = info
        setTaskDescription(ActivityManager.TaskDescription(info.name))
        lifecycleScope.launch {
            val icon = info.iconUrl?.let { url -> runCatching {
                SingletonImageLoader.get(this@MiniAppActivity).execute(ImageRequest.Builder(this@MiniAppActivity)
                    .data(url).size(128).build()).image?.toBitmap()
            }.getOrNull() }
            if (!isFinishing && info.id == request?.key?.appId) setTaskDescription(ActivityManager.TaskDescription(info.name, icon))
        }
    }

    private fun openBrowser(value: String) {
        val target = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(value))
        val browser = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.org")),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeUnless { it == packageName || it == "android" }
        runCatching {
            startActivity(if (browser != null) target.setPackage(browser) else Intent.createChooser(target, "浏览器打开")
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(this, MainActivity::class.java))))
        }.onFailure { android.widget.Toast.makeText(this, "没有可打开此链接的浏览器", android.widget.Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        request?.key?.let { MiniAppTasks.detach(it, this) }
        super.onDestroy()
    }
}
