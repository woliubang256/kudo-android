package com.kudo.android.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.kudo.android.data.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal const val APK_MIME = "application/vnd.android.package-archive"
internal fun DownloadTask.isApk() = fileName.endsWith(".apk", true) || mimeType.equals(APK_MIME, true)
internal data class ApkInstallRequest(val task: DownloadTask, val release: GitHubRelease? = null)
internal data class PreparedApk(val uri: String)

/** Copies a completed download before inspection so the installer reads the inspected snapshot. */
internal class ApkInstaller(private val context: Context) {
    @Suppress("DEPRECATION")
    suspend fun prepare(request: ApkInstallRequest): PreparedApk = withContext(Dispatchers.IO) {
        val task = request.task
        if (task.status != DownloadStatus.COMPLETED || task.fileUri == null) throw IOException("安装包尚未下载完成")
        val directory = File(context.cacheDir, "apk_installs").apply { mkdirs() }
        directory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000L }
            ?.forEach { it.delete() }
        val snapshot = File(directory, "${UUID.randomUUID()}.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(task.fileUri.toUri())?.use { input ->
                snapshot.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > 1_073_741_824L) throw IOException("安装包过大，无法安装")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            } ?: throw IOException("安装包已不可用，请重新下载")
            request.release?.apk?.let { apk ->
                if (size != apk.size) throw IOException("安装包大小不匹配，请重新下载")
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (apk.sha256 != null && apk.sha256 != sha256) throw IOException("安装包校验失败，请重新下载")
            }
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = context.packageManager.getPackageArchiveInfo(snapshot.path, flags)
                ?: throw IOException("安装包无效，请重新下载")
            if (request.release != null && archive.packageName != context.packageName) throw IOException("安装包不属于酷DO，请重新下载")
            if (archive.packageName == context.packageName) {
                val installed = context.packageManager.getPackageInfo(context.packageName, flags)
                if (versionCode(archive) < versionCode(installed)) throw IOException("安装包版本低于当前版本")
                if (request.release != null && archive.versionName != request.release.version) throw IOException("安装包版本与更新信息不一致")
                if (!compatibleSigners(signers(installed), signers(archive), signerHistory(archive)))
                    throw IOException("安装包签名与当前应用不一致，无法覆盖安装")
            }
            PreparedApk(FileProvider.getUriForFile(context, "${context.packageName}.image-files", snapshot).toString())
        } catch (failure: Throwable) {
            snapshot.delete()
            if (failure is FileNotFoundException || failure is SecurityException)
                throw IOException("安装包已不可用，请重新下载", failure)
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { it.toCharsString() }.toSet()

    private fun signerHistory(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo?.hasMultipleSigners() == false)
        info.signingInfo?.signingCertificateHistory.orEmpty().map { it.toCharsString() }.toSet() else emptySet()
}

internal fun compatibleSigners(installed: Set<String>, candidate: Set<String>, candidateHistory: Set<String>): Boolean =
    installed.isNotEmpty() && candidate.isNotEmpty() &&
        (installed == candidate || (installed.size == 1 && candidate.size == 1 && candidateHistory.containsAll(installed)))

internal fun apkInstallIntent(apk: PreparedApk) = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(apk.uri.toUri(), APK_MIME)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
