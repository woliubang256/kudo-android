package com.kudo.android.ui.account

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.media.DownloadStatus
import com.kudo.android.media.DownloadTask
import com.kudo.android.media.NativeDownloadManager
import com.kudo.android.media.DOWNLOAD_VERIFICATION_MESSAGE
import com.kudo.android.media.ApkInstallRequest
import com.kudo.android.media.isApk
import com.kudo.android.ui.components.CommunityIconButton
import com.kudo.android.ui.components.CommunityButton
import com.kudo.android.ui.components.EmptyState
import com.kudo.android.ui.components.showKudoToast
import com.kudo.android.ui.components.MaterialPageScaffold
import kotlin.math.roundToInt

@Composable
internal fun DownloadsScreen(manager: NativeDownloadManager, back: () -> Unit) {
    val tasks by manager.tasks.collectAsState()
    val context = LocalContext.current
    val list = tasks.sortedByDescending { it.createdAt }
    MaterialPageScaffold(modifier = Modifier.testTag("downloads-screen"), header = {
        AccountPageTopBar("下载管理", back) {
            if (list.any { it.status != DownloadStatus.DOWNLOADING }) {
                CommunityIconButton(Icons.Rounded.DeleteSweep, "清理已完成任务", {
                    list.filter { it.status != DownloadStatus.DOWNLOADING }.forEach { manager.remove(it.id) }
                })
            }
        }
    }) { padding ->
        if (list.isEmpty()) {
            Box(Modifier.padding(padding)) { EmptyState("暂无下载任务", "帖子里的压缩包和文件会显示在这里。") }
        } else LazyColumn(
            state = rememberLazyListState(), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.id }) { task -> DownloadTaskRow(task, manager, context) }
        }
    }
}

@Composable
private fun DownloadTaskRow(task: DownloadTask, manager: NativeDownloadManager, context: Context) {
    val verification = LocalWebVerification.current
    val install = LocalApkInstall.current
    val progress = task.totalBytes?.takeIf { it > 0 }?.let { (task.bytes * 100f / it).coerceIn(0f, 100f) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 16.dp, vertical = 13.dp).testTag("download-${task.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(when (task.status) {
                DownloadStatus.DOWNLOADING -> Icons.Rounded.Download
                DownloadStatus.COMPLETED -> Icons.Rounded.CheckCircle
                DownloadStatus.FAILED -> Icons.Rounded.ErrorOutline
                DownloadStatus.CANCELED -> Icons.Rounded.Cancel
            }, null, Modifier.size(22.dp), tint = when (task.status) {
                DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
            Text(task.fileName, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            CommunityIconButton(Icons.Rounded.DeleteOutline, "删除下载记录", { manager.remove(task.id) })
        }
        when (task.status) {
            DownloadStatus.DOWNLOADING -> {
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(progress?.let { "${it.roundToInt()}%" } ?: "下载中", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CommunityButton("取消", { manager.cancel(task.id) }, compact = true)
                }
            }
            DownloadStatus.COMPLETED -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("下载完成", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CommunityButton(if (task.isApk()) "安装" else "打开", {
                    if (task.isApk() && install != null) {
                        install(ApkInstallRequest(task))
                        return@CommunityButton
                    }
                    val intent = manager.openIntent(task)
                    if (intent == null) showKudoToast(context, "文件已不可用")
                    else try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { showKudoToast(context, "没有可打开此文件的应用") }
                    catch (_: SecurityException) { showKudoToast(context, "文件已不可用") }
                }, compact = true)
            }
            DownloadStatus.FAILED, DownloadStatus.CANCELED -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(task.error ?: "下载未完成", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (task.error == DOWNLOAD_VERIFICATION_MESSAGE && verification != null) {
                    CommunityButton("验证", { verification { manager.retry(task.id) } }, compact = true)
                } else CommunityButton("重试", { manager.retry(task.id) }, compact = true)
            }
        }
    }
}
