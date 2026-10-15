package com.kudo.android.media

import android.Manifest
import androidx.core.content.edit
import androidx.core.net.toUri
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
import com.kudo.android.data.isCloudflareChallenge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
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
internal const val DOWNLOAD_VERIFICATION_MESSAGE = "网站要求验证，请在设置中手动过盾后重试"

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
    private val failureObserver: (Exception) -> Unit = {},
    private val keepAlive: () -> Unit = {},
    notificationChannel: String = "downloads",
) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = ConcurrentHashMap<String, Call>()
    private val notifier = DownloadNotifier(app, notificationChannel)
    private val store = DownloadFileStore(app)
    private val state = MutableStateFlow(loadTasks())
    val tasks: StateFlow<List<DownloadTask>> = state.asStateFlow()
    private val completionEvents = Channel<DownloadEvent>(Channel.UNLIMITED)
    val events = completionEvents.receiveAsFlow()

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
        keepAlive()
        scope.launch { run(task) }
        return id
    }

    @Synchronized fun cancel(id: String) {
        val task = state.value.firstOrNull { it.id == id } ?: return
        if (task.status != DownloadStatus.DOWNLOADING) return
        calls[id]?.cancel()
        updateTask(id) { it.copy(status = DownloadStatus.CANCELED, error = "已取消") }
        notifier.cancel(task)
    }

    @Synchronized fun remove(id: String, deleteFile: Boolean = false) {
        val task = state.value.firstOrNull { it.id == id } ?: return
        if (task.status == DownloadStatus.DOWNLOADING) cancel(id)
        if (deleteFile) task.fileUri?.let(store::delete)
        updateTasks { filterNot { it.id == id } }
        notifier.cancel(task)
    }

    @Synchronized fun retry(id: String): String? {
        val task = state.value.firstOrNull { it.id == id } ?: return null
        if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.COMPLETED) return null
        remove(id)
        return enqueue(task.url)
    }

    private fun checkActive(id: String) {
        if (state.value.none { it.id == id && it.status == DownloadStatus.DOWNLOADING }) throw CancellationException("下载已取消")
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
        val request = downloadRequest(Request.Builder().url(url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", userAgent)
            .header("Referer", "https://linux.do/")
            .build(), cookies, userAgent)
        try {
            checkActive(initial.id)
            val call = http.newCall(request)
            synchronized(this) {
                checkActive(initial.id)
                calls[initial.id] = call
            }
            call.execute().use { response ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                checkActive(initial.id)
                if (!response.isSuccessful) {
                    if (isCloudflareChallenge(response, response.peekBody(8192).string())) throw IOException(DOWNLOAD_VERIFICATION_MESSAGE)
                    throw IOException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("下载内容为空")
                val name = response.header("Content-Disposition")?.let(::filenameFromContentDisposition)
                    ?: initial.fileName
                val mime = response.header("Content-Type")?.substringBefore(';')?.trim()
                    ?.takeIf(String::isNotBlank) ?: initial.mimeType
                val total = body.contentLength().takeIf { it >= 0 }
                val source = body.source()
                source.request(512)
                val prefix = source.peek().readByteArray(minOf(512, source.buffer.size)).toString(Charsets.UTF_8).trimStart()
                if (mime.equals("text/html", true) || mime.equals("application/xhtml+xml", true) ||
                    response.header("cf-mitigated") == "challenge" || prefix.startsWith("<!doctype html", true) || prefix.startsWith("<html", true)) {
                    throw IOException(DOWNLOAD_VERIFICATION_MESSAGE)
                }
                val target = store.create(name, mime)
                var bytes = 0L
                var lastPublished = System.nanoTime()
                try {
                    synchronized(this) {
                        checkActive(initial.id)
                        updateTask(initial.id) { it.copy(fileName = name, totalBytes = total, mimeType = mime, fileUri = target.uri) }
                    }
                    body.byteStream().use { input ->
                        target.output.use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                checkActive(initial.id)
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                bytes += count
                                if (System.nanoTime() - lastPublished >= 500_000_000L) {
                                    lastPublished = System.nanoTime()
                                    synchronized(this) {
                                        checkActive(initial.id)
                                        updateTask(initial.id, persist = false) { it.copy(bytes = bytes) }
                                    }
                                }
                            }
                        }
                    }
                    if (total != null && bytes != total) throw IOException("文件传输不完整，请重试")
                    val completed = initial.copy(fileName = name, bytes = bytes, totalBytes = total, mimeType = mime,
                        status = DownloadStatus.COMPLETED, fileUri = target.uri)
                    synchronized(this) {
                        checkActive(initial.id)
                        target.commit()
                        updateTask(initial.id) { completed }
                        if (!notifier.completed(completed)) completionEvents.trySend(DownloadEvent.Completed(name))
                    }
                } catch (error: Throwable) {
                    target.delete()
                    throw error
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(this) { updateTask(initial.id) { it.copy(status = DownloadStatus.CANCELED, fileUri = null, error = "已取消") } }
        } catch (failure: Exception) {
            failureObserver(failure)
            synchronized(this) {
                if (state.value.any { it.id == initial.id && it.status == DownloadStatus.DOWNLOADING }) {
                    fail(initial, when {
                        failure.message.orEmpty().startsWith("HTTP ") -> "下载失败（${failure.message}）"
                        failure.message.orEmpty().contains("DoH:") -> com.kudo.android.network.dohErrorMessage(failure as? IOException ?: IOException(failure))
                        failure.message == DOWNLOAD_VERIFICATION_MESSAGE -> DOWNLOAD_VERIFICATION_MESSAGE
                        else -> "下载失败，请稍后重试"
                    })
                } else updateTask(initial.id) { it.copy(fileUri = null) }
            }
        } finally {
            calls.remove(initial.id)
        }
    }

    private fun fail(task: DownloadTask, message: String) {
        val failed = task.copy(status = DownloadStatus.FAILED, error = message)
        updateTask(task.id) { failed }
        if (!notifier.failed(failed)) completionEvents.trySend(DownloadEvent.Failed(task.fileName))
    }

    private fun updateTask(id: String, persist: Boolean = true, transform: (DownloadTask) -> DownloadTask) =
        updateTasks(persist) { map { if (it.id == id) transform(it) else it } }

    @Synchronized private fun updateTasks(persist: Boolean = true, transform: List<DownloadTask>.() -> List<DownloadTask>) {
        state.update { it.transform() }
        if (persist) saveTasks(state.value)
    }

    private fun loadTasks(): List<DownloadTask> = runCatching {
        val values = JSONArray(preferences.getString(KEY_TASKS, "[]"))
        buildList {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                val status = DownloadStatus.valueOf(value.optString("status"))
                if (status != DownloadStatus.COMPLETED) value.optString("fileUri").takeIf(String::isNotBlank)?.let { runCatching { store.delete(it) } }
                add(DownloadTask(value.getString("id"), value.getString("url"), value.getString("fileName"),
                    if (status == DownloadStatus.DOWNLOADING) DownloadStatus.FAILED else status,
                    value.optLong("bytes"), value.optLong("totalBytes").takeIf { it > 0 },
                    value.optString("mimeType", "application/octet-stream"), if (status != DownloadStatus.COMPLETED) null else value.optString("fileUri").takeIf(String::isNotBlank),
                    if (status == DownloadStatus.DOWNLOADING) "下载已中断，请重试" else value.optString("error").takeIf(String::isNotBlank), value.optLong("createdAt")))
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
        preferences.edit { putString(KEY_TASKS, values.toString()) }
    }

    companion object {
        private const val PREFERENCES = "native_downloads"
        private const val KEY_TASKS = "tasks"
        private const val MAX_TASKS = 100
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
    return sanitizeDownloadFilename(segment).ifBlank { "download-${System.currentTimeMillis()}" }
}

internal fun filenameFromContentDisposition(value: String): String? {
    val encoded = Regex("filename\\*\\s*=\\s*UTF-8'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)
    val plain = Regex("filename\\s*=\\s*\\\"([^\\\"]+)\\\"|filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
        .find(value)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
    return sanitizeDownloadFilename(if (encoded != null) decodeFilename(encoded.trim().trim('"')) else plain.orEmpty()).ifBlank { null }
}

private fun decodeFilename(value: String): String = runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrDefault(value)

/** Re-evaluated on every redirect hop, so community credentials never follow a CDN redirect. */
internal fun downloadRequest(request: Request, cookies: SessionCookies, userAgent: String): Request {
    val builder = request.newBuilder().removeHeader("Cookie").header("User-Agent", userAgent)
    if (request.url.isHttps && request.url.host == "linux.do" && request.url.port == 443) {
        cookies.get(request.url.toString()).takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
    }
    return builder.build()
}

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
            val output = try {
                context.contentResolver.openOutputStream(uri) ?: throw IOException("无法打开下载文件")
            } catch (failure: Exception) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw failure
            }
            return Target(uri.toString(), output,
                onCommit = {
                    if (context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 0) {
                        throw IOException("无法完成文件保存")
                    }
                },
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
        val uri = value.toUri()
        if (uri.authority == "${context.packageName}.image-files") {
            uri.lastPathSegment?.let { File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, it).delete() }
        } else if (uri.scheme == "content") context.contentResolver.delete(uri, null, null) else uri.path?.let(::File)?.delete()
    }

    class Target(val uri: String, val output: OutputStream, private val onCommit: () -> Unit, private val onDelete: () -> Unit) {
        fun commit() = onCommit()
        fun delete() = onDelete()
    }
}

internal class DownloadNotifier(private val context: Context, private val channel: String = "downloads") {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init { manager.createNotificationChannel(NotificationChannel(channel, "文件下载", NotificationManager.IMPORTANCE_LOW)) }

    fun enabled(): Boolean = manager.areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

    fun progressNotification(task: DownloadTask?, count: Int): Notification {
        val percent = task?.downloadPercent()
        return Notification.Builder(context, channel)
            .setSmallIcon(R.mipmap.ic_notification_color).setContentTitle(task?.fileName ?: "文件下载")
            .setContentText(if (task == null) "准备下载" else "下载中${percent?.let { " $it%" }.orEmpty()} · $count 项任务")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(pendingIntent()).build()
    }

    fun completed(task: DownloadTask): Boolean = post(task, Notification.Builder(context, channel).setSmallIcon(R.mipmap.ic_notification_color)
            .setContentTitle(task.fileName).setContentText("下载完成").setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent()).build())

    fun failed(task: DownloadTask): Boolean = post(task, Notification.Builder(context, channel).setSmallIcon(R.mipmap.ic_notification_color)
            .setContentTitle(task.fileName).setContentText(task.error ?: "下载失败").setAutoCancel(true).setContentIntent(pendingIntent()).build())

    private fun post(task: DownloadTask, value: Notification): Boolean {
        if (!enabled()) return false
        return try { manager.notify(task.id.hashCode(), value); true } catch (_: SecurityException) { false }
    }

    fun cancel(task: DownloadTask) { manager.cancel(task.id.hashCode()) }

    private fun pendingIntent(): PendingIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setAction(ACTION)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val ACTION = "com.kudo.android.OPEN_DOWNLOADS"
    }
}

internal fun DownloadTask.downloadPercent(): Int? = totalBytes?.takeIf { it > 0 }?.let {
    (bytes.toDouble() / it * 100).toInt().coerceIn(0, 100)
}
