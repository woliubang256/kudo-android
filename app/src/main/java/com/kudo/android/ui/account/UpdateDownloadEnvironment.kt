package com.kudo.android.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kudo.android.data.GitHubRelease
import com.kudo.android.media.*
import com.kudo.android.ui.components.CommunityButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal val LocalUpdateDownloadActions = staticCompositionLocalOf<(@Composable (GitHubRelease) -> Unit)?> { null }

internal class UpdateDownloadViewModel(private val manager: NativeDownloadManager) : ViewModel() {
    private var watched: Pair<String, GitHubRelease>? = null
    private val pending = MutableStateFlow<ApkInstallRequest?>(null)
    val install = pending.asStateFlow()
    val tasks = manager.tasks

    init { viewModelScope.launch { tasks.collect { publishCompleted() } } }

    fun download(release: GitHubRelease) {
        val apk = release.apk ?: return
        val existing = tasks.value.firstOrNull { it.url == apk.url }
        val id = when (existing?.status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED -> existing.id
            DownloadStatus.FAILED, DownloadStatus.CANCELED -> manager.retry(existing.id)
            null -> manager.enqueue(apk.url)
        } ?: return
        watched = id to release
        publishCompleted()
    }

    fun restart(release: GitHubRelease) {
        val apk = release.apk ?: return
        tasks.value.filter { it.url == apk.url }.forEach { manager.remove(it.id) }
        download(release)
    }

    fun cancel(id: String) {
        if (watched?.first == id) watched = null
        manager.cancel(id)
    }

    fun consumeInstall() { pending.value = null }

    private fun publishCompleted() {
        val (id, release) = watched ?: return
        val task = tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status == DownloadStatus.COMPLETED) {
            watched = null
            pending.value = ApkInstallRequest(task, release)
        }
    }
}

@Composable
internal fun UpdateDownloadEnvironment(manager: NativeDownloadManager, openExternal: (String) -> Unit,
    content: @Composable () -> Unit) {
    val model: UpdateDownloadViewModel = viewModel(key = "update-download", factory = viewModelFactory {
        initializer { UpdateDownloadViewModel(manager) }
    })
    val install = LocalApkInstall.current
    val pending by model.install.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(model) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(resumed, pending, install) {
        val request = pending
        if (resumed && request != null && install != null) {
            model.consumeInstall()
            install(request)
        }
    }
    CompositionLocalProvider(LocalUpdateDownloadActions provides { release ->
        UpdateDownloadActions(release, model, openExternal)
    }) { content() }
}

@Composable
internal fun UpdateDownloadActions(release: GitHubRelease, model: UpdateDownloadViewModel, openExternal: (String) -> Unit) {
    val tasks by model.tasks.collectAsStateWithLifecycle()
    val apk = release.apk
    val task = apk?.let { asset -> tasks.firstOrNull { it.url == asset.url } }
    val preparing = LocalApkPreparing.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (apk == null) {
            Text("此版本暂无可用安装包", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModalLink("在 GitHub 查看此版本") { openExternal(release.url) }
        } else when (task?.status) {
            DownloadStatus.DOWNLOADING -> {
                val percent = task.downloadPercent()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (percent == null) "正在下载安装包…" else "正在下载 $percent%", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    CommunityButton("取消下载", { model.cancel(task.id) }, compact = true, shape = RoundedCornerShape(50))
                }
            }
            DownloadStatus.COMPLETED -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CommunityButton(if (preparing) "正在校验安装包…" else "安装更新", { model.download(release) },
                        Modifier.weight(1f), enabled = !preparing, shape = RoundedCornerShape(50))
                    Text("重新下载", Modifier.clickable(enabled = !preparing, role = Role.Button) { model.restart(release) }
                        .padding(vertical = 14.dp, horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            DownloadStatus.FAILED, DownloadStatus.CANCELED -> {
                Text(task.error ?: "下载未完成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CommunityButton("重新下载更新", { model.download(release) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(50))
            }
            null -> CommunityButton("下载更新", { model.download(release) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(50))
        }
    }
}
