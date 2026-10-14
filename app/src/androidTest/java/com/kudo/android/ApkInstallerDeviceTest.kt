package com.kudo.android

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.GitHubApk
import com.kudo.android.data.GitHubRelease
import com.kudo.android.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ApkInstallerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source get() = File(context.applicationInfo.sourceDir)
    private fun task(file: File = source) = DownloadTask("fixture", "https://example.org/app.apk", "app.apk", DownloadStatus.COMPLETED,
        fileUri = Uri.fromFile(file).toString(), mimeType = "application/octet-stream")
    private fun release(size: Long = source.length(), version: String = BuildConfig.VERSION_NAME, digest: String? = null) =
        GitHubRelease(version, "更新", "", "", "", GitHubApk("app.apk", "https://example.org/app.apk", size, digest))

    @Test fun installedSignedApkIsCopiedVerifiedAndSharedWithTemporaryReadPermission() = runBlocking {
        val sha = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        val apk = ApkInstaller(context).prepare(ApkInstallRequest(task(), release(digest = sha)))
        try {
            assertTrue(apk.uri.startsWith("content://${context.packageName}.image-files/apk_installs/"))
            assertEquals(source.length(), context.contentResolver.openAssetFileDescriptor(Uri.parse(apk.uri), "r")!!.use { it.length })
            val intent = apkInstallIntent(apk)
            assertEquals(Intent.ACTION_VIEW, intent.action); assertEquals(APK_MIME, intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally { context.contentResolver.delete(Uri.parse(apk.uri), null, null) }
    }

    @Test fun wrongSizeDigestVersionAndPackageNeverProduceInstallableUri() = runBlocking {
        for ((expected, text) in listOf(release(size = 1) to "大小不匹配", release(digest = "0".repeat(64)) to "校验失败",
            release(version = "9.9.9") to "版本与更新信息不一致")) {
            try { ApkInstaller(context).prepare(ApkInstallRequest(task(), expected)); fail("应拒绝错误安装包") }
            catch (e: IOException) { assertTrue(e.message, e.message.orEmpty().contains(text)) }
        }
        val other = File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        try { ApkInstaller(context).prepare(ApkInstallRequest(task(other), release(size = other.length()))); fail("应拒绝其他包名") }
        catch (e: IOException) { assertTrue(e.message.orEmpty().contains("不属于酷DO")) }
    }

    @Test fun invalidAndIncompleteDownloadsCannotOpenInstaller() = runBlocking {
        val invalid = File(context.cacheDir, "invalid-update-fixture.apk").apply { writeText("invalid apk") }
        try {
            try { ApkInstaller(context).prepare(ApkInstallRequest(task(invalid))); fail("应拒绝无效 APK") }
            catch (e: IOException) { assertTrue(e.message.orEmpty().contains("安装包无效")) }
            try { ApkInstaller(context).prepare(ApkInstallRequest(task().copy(status = DownloadStatus.DOWNLOADING))); fail("应等待完成") }
            catch (e: IOException) { assertTrue(e.message.orEmpty().contains("尚未下载完成")) }
        } finally { invalid.delete() }
    }

    @Test fun publishedReleaseWithDifferentCertificateCannotReplaceDevelopmentApp() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("mismatchedApk")
        org.junit.Assume.assumeTrue("需提供不同签名的正式包夹具", path != null)
        val file = File(path!!)
        try {
            ApkInstaller(context).prepare(ApkInstallRequest(task(file), release(size = file.length(), version = "0.2.4")))
            fail("开发签名不能被正式签名覆盖")
        } catch (e: IOException) { assertTrue(e.message, e.message.orEmpty().contains("签名与当前应用不一致")) }
    }

    @Test fun removedDownloadReportsActionableMessageWithoutFilesystemDetails() = runBlocking {
        val missing = File(context.cacheDir, "missing-update-fixture.apk")
        assertFalse(missing.exists())
        try { ApkInstaller(context).prepare(ApkInstallRequest(task(missing))); fail("文件已删除应提示重下") }
        catch (e: IOException) { assertEquals("安装包已不可用，请重新下载", e.message) }
    }
}
