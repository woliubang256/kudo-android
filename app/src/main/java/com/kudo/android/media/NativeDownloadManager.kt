package com.kudo.android.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.kudo.android.MainActivity
import com.kudo.android.R
import com.kudo.android.data.SessionCookies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.net.URLDecoder

internal enum class DownloadStatus { DOWNLOADING, COMPLETED, FAILED, CANCELED }

internal data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val status: DownloadStatus,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
    val mimeType: String = "application/octet-stream",
    val fileUri: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

internal sealed interface DownloadEvent {
    data class Completed(val fileName: String) : DownloadEvent
    data class Failed(val fileName: String) : DownloadEvent
}

/** Native file downloads routed through the same DoH/ECH Call.Factory as API requests. */
internal class NativeDownloadManager(
    context: Context,
    private val http: Call.Factory,
    private val cookies: SessionCookies,
    private val userAgent: String,
) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = ConcurrentHashMap<String, Call>()
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()
    private val notifier = DownloadNotifier(app)
    private val store = DownloadFileStore(app)
    private val state = MutableStateFlow(loadTasks())
    val tasks: StateFlow<List<DownloadTask>> = state.asStateFlow()
    val events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 8)

    fun isLikelyDownload(value: String): Boolean {
        return isDownloadUrl(value)
    }

    fun enqueue(value: String): String? {
        val url = value.trim().toHttpUrlOrNull()?.takeIf {
            it.scheme in setOf("http", "https") && it.username.isEmpty() && it.password.isEmpty()
        } ?: return null
        val id = UUID.randomUUID().toString()
        val task = DownloadTask(id, url.toString(), filenameFromUrl(url.toString()), DownloadStatus.DOWNLOADING)
        updateTasks { listOf(task) + this }
        notifier.progress(task)
        scope.launch { run(task) }
        return id
    }

    fun cancel(id: String) {
        val task = state.value.firstOrNull { it.id == id } ?: return
        if (task.status != DownloadStatus.DOWNLOADING) return
        cancelRequested += id
        calls[id]?.cancel()
        updateTask(id) { it.copy(status = DownloadStatus.CANCELED, error = "已取消") }
        notifier.cancel(task)
    }

    fun remove(id: String) {
        val task = state.value.firstOrNull { it.id == id } ?: return
        if (task.status == DownloadStatus.DOWNLOADING) cancel(id)
        task.fileUri?.let(store::delete)
        updateTasks { filterNot { it.id == id } }
    }

    fun openIntent(task: DownloadTask): Intent? {
        val uri = task.fileUri?.let(Uri::parse) ?: return null
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setDataAndType(uri, task.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private suspend fun run(initial: DownloadTask) {
        val url = initial.url.toHttpUrlOrNull() ?: return fail(initial, "下载地址无效")
        val request = Request.Builder().url(url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", userAgent)
            .header("Referer", "https://linux.do/")
            .apply { cookies.get(url.toString()).takeIf(String::isNotBlank)?.let { header("Cookie", it) } }
            .build()
        val call = http.newCall(request)
        calls[initial.id] = call
        try {
            call.execute().use { response ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("下载内容为空")
                val name = response.header("Content-Disposition")?.let(::filenameFromContentDisposition)
                    ?: initial.fileName
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim()
                    ?.takeIf(String::isNotBlank) ?: initial.mimeType
                val total = body.contentLength().takeIf { it >= 0 }
                val target = store.create(name, mime)
                var bytes = 0L
                var lastPublished = 0L
                try {
                    body.byteStream().use { input ->
                        target.output.use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                bytes += count
                                if (bytes - lastPublished >= 256 * 1024 || (total != null && bytes == total)) {
                                    lastPublished = bytes
                                    val progress = initial.copy(fileName = name, bytes = bytes, totalBytes = total, mimeType = mime)
                                    updateTask(initial.id) { progress }
                                    notifier.progress(progress)
                                }
                            }
                        }
                    }
                    target.commit()
                    val completed = initial.copy(fileName = name, bytes = bytes, totalBytes = total, mimeType = mime,
                        status = DownloadStatus.COMPLETED, fileUri = target.uri)
                    updateTask(initial.id) { completed }
                    notifier.completed(completed)
                    if (!notifier.enabled()) events.tryEmit(DownloadEvent.Completed(name))
                } catch (error: Throwable) {
                    target.delete()
                    throw error
                }
            }
        } catch (cancelled: CancellationException) {
            if (!cancelRequested.contains(initial.id)) fail(initial, "下载已取消")
        } catch (_: Throwable) {
            if (cancelRequested.contains(initial.id)) updateTask(initial.id) { it.copy(status = DownloadStatus.CANCELED, error = "已取消") }
            else fail(initial, "下载失败，请稍后重试")
        } finally {
            calls.remove(initial.id)
            cancelRequested.remove(initial.id)
        }
    }

    private fun fail(task: DownloadTask, message: String) {
        val failed = task.copy(status = DownloadStatus.FAILED, error = message)
        updateTask(task.id) { failed }
        notifier.failed(failed)
        if (!notifier.enabled()) events.tryEmit(DownloadEvent.Failed(task.fileName))
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) =
        updateTasks { map { if (it.id == id) transform(it) else it } }

    private fun updateTasks(transform: List<DownloadTask>.() -> List<DownloadTask>) {
        state.update { it.transform() }
        saveTasks(state.value)
    }

    private fun loadTasks(): List<DownloadTask> = runCatching {
        val values = JSONArray(preferences.getString(KEY_TASKS, "[]"))
        buildList {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                val status = DownloadStatus.valueOf(value.optString("status"))
                add(DownloadTask(value.getString("id"), value.getString("url"), value.getString("fileName"),
                    if (status == DownloadStatus.DOWNLOADING) DownloadStatus.FAILED else status,
                    value.optLong("bytes"), value.optLong("totalBytes").takeIf { it > 0 },
                    value.optString("mimeType", "application/octet-stream"), value.optString("fileUri").takeIf(String::isNotBlank),
                    value.optString("error").takeIf(String::isNotBlank), value.optLong("createdAt")))
            }
        }
    }.getOrDefault(emptyList())

    private fun saveTasks(tasks: List<DownloadTask>) {
        val values = JSONArray().apply { tasks.take(MAX_TASKS).forEach { task ->
            put(JSONObject().apply {
                put("id", task.id); put("url", task.url); put("fileName", task.fileName); put("status", task.status.name)
                put("bytes", task.bytes); put("totalBytes", task.totalBytes ?: 0); put("mimeType", task.mimeType)
                put("fileUri", task.fileUri.orEmpty()); put("error", task.error.orEmpty()); put("createdAt", task.createdAt)
            })
        } }
        preferences.edit().putString(KEY_TASKS, values.toString()).apply()
    }

    companion object {
        private const val PREFERENCES = "native_downloads"
        private const val KEY_TASKS = "tasks"
        private const val MAX_TASKS = 100
        private val DOWNLOAD_EXTENSIONS = setOf(
            ".zip", ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz", ".apk", ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".txt", ".csv", ".json", ".xml", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".mov",
        )
    }
}

internal fun isDownloadUrl(value: String): Boolean {
    val url = value.trim().toHttpUrlOrNull() ?: return false
    if (url.scheme !in setOf("http", "https") || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
    val candidate = url.pathSegments.lastOrNull().orEmpty().lowercase()
    return setOf(
        ".zip", ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz", ".apk", ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".txt", ".csv", ".json", ".xml", ".mp3", ".m4a", ".wav", ".mp4", ".mkv", ".mov",
    ).any { candidate.endsWith(it) } || url.queryParameterNames.any { it.lowercase() in setOf("download", "filename", "file") }
}

internal fun filenameFromUrl(value: String): String {
    val segment = value.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
    return sanitizeDownloadFilename(decodeFilename(segment)).ifBlank { "download-${System.currentTimeMillis()}" }
}

internal fun filenameFromContentDisposition(value: String): String? {
    val encoded = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)
    val plain = Regex("filename\\s*=\\s*\\\"([^\\\"]+)\\\"|filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
        .find(value)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
    return sanitizeDownloadFilename(decodeFilename(encoded ?: plain.orEmpty())).ifBlank { null }
}

private fun decodeFilename(value: String): String = runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

internal fun sanitizeDownloadFilename(value: String): String = value
    .substringAfterLast('/')
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
    .trim(' ', '.')
    .take(128)

private class DownloadFileStore(private val context: Context) {
    fun create(name: String, mime: String): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, sanitizeDownloadFilename(name))
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法创建下载文件")
            val output = context.contentResolver.openOutputStream(uri) ?: run {
                context.contentResolver.delete(uri, null, null)
                throw IOException("无法打开下载文件")
            }
            return Target(uri.toString(), output,
                onCommit = { context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) },
                onDelete = { context.contentResolver.delete(uri, null, null) })
        }
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建下载目录")
        val file = File(directory, sanitizeDownloadFilename(name)).let { candidate ->
            if (!candidate.exists()) candidate else File(directory, "${candidate.nameWithoutExtension}-${System.currentTimeMillis()}.${candidate.extension}")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.image-files", file, file.name)
        return Target(uri.toString(), file.outputStream(), {}, { file.delete() })
    }

    fun delete(value: String) {
        val uri = Uri.parse(value)
        if (uri.authority == "${context.packageName}.image-files") {
            uri.lastPathSegment?.let { File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, it).delete() }
        } else if (uri.scheme == "content") context.contentResolver.delete(uri, null, null) else uri.path?.let(::File)?.delete()
    }

    class Target(val uri: String, val output: OutputStream, private val onCommit: () -> Unit, private val onDelete: () -> Unit) {
        fun commit() = onCommit()
        fun delete() = onDelete()
    }
}

private class DownloadNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init { manager.createNotificationChannel(NotificationChannel(CHANNEL, "文件下载", NotificationManager.IMPORTANCE_LOW)) }

    fun enabled(): Boolean = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun progress(task: DownloadTask) {
        if (!enabled()) return
        val total = task.totalBytes?.takeIf { it > 0 }
        val value = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(task.fileName)
            .setContentText(total?.let { "下载中 ${(task.bytes * 100 / it).toInt().coerceIn(0, 100)}%" } ?: "下载中")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(total?.toInt() ?: 0, if (total == null) 0 else task.bytes.coerceAtMost(total).toInt(), total == null)
            .setContentIntent(pendingIntent()).build()
        manager.notify(task.id.hashCode(), value)
    }

    fun completed(task: DownloadTask) {
        if (!enabled()) return
        manager.notify(task.id.hashCode(), Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.fileName).setContentText("下载完成").setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent()).build())
    }

    fun failed(task: DownloadTask) {
        if (!enabled()) return
        manager.notify(task.id.hashCode(), Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.fileName).setContentText(task.error ?: "下载失败").setAutoCancel(true).build())
    }

    fun cancel(task: DownloadTask) { manager.cancel(task.id.hashCode()) }

    private fun pendingIntent(): PendingIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object { private const val CHANNEL = "downloads" }
}
