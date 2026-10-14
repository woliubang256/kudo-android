package com.kudo.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

const val KudoSourceUrl = "https://github.com/woliubang256/kudo-android"

internal data class StableVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int =
        compareValuesBy(this, other, StableVersion::major, StableVersion::minor, StableVersion::patch)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        fun parse(value: String): StableVersion? {
            val match = Regex("v?(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})").matchEntire(value) ?: return null
            val (major, minor, patch) = match.destructured
            return StableVersion(major.toInt(), minor.toInt(), patch.toInt())
                .takeIf { it.major * 1_000_000L + it.minor * 1000L + it.patch + 1 <= 2_100_000_000L }
        }
    }
}

data class GitHubApk(val name: String, val url: String, val size: Long, val sha256: String? = null)

data class GitHubRelease(val version: String, val name: String, val notes: String, val publishedAt: String, val url: String,
    val apk: GitHubApk? = null) {
    fun isNewerThan(current: String): Boolean =
        (StableVersion.parse(version) ?: throw UpdateFailure("无法识别最新版本号")) >
            (StableVersion.parse(current) ?: throw UpdateFailure("无法识别当前版本号，请到仓库查看"))
}

class UpdateFailure(override val message: String) : IOException(message)

/** Public GitHub reads never share the community's authenticated HTTP client. */
class GitHubUpdates(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val endpoint: HttpUrl = "https://api.github.com/repos/woliubang256/kudo-android/releases/latest".toHttpUrl(),
) {
    suspend fun latest(): GitHubRelease {
        val request = Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Kudo-Android").build()
        val response = try { client.newCall(request).awaitPublicResponse() }
        catch (e: IOException) { throw UpdateFailure("无法连接 GitHub，请检查网络后重试") }
        return response.use {
            withContext(Dispatchers.IO) {
                when (it.code) {
                    404 -> throw UpdateFailure("暂无公开版本，仓库可能尚未公开或尚未发布正式版本")
                    403, 429 -> throw UpdateFailure("GitHub 暂时限制请求，请稍后重试")
                }
                if (!it.isSuccessful) throw UpdateFailure("GitHub 暂不可用，请稍后重试（${it.code}）")
                try { parseRelease(JSONObject(it.body?.string().orEmpty())) }
                catch (e: UpdateFailure) { throw e }
                catch (e: Exception) { throw UpdateFailure("无法读取版本信息，请到仓库查看") }
            }
        }
    }
}

internal fun parseRelease(json: JSONObject): GitHubRelease {
    if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw UpdateFailure("暂未提供可用的正式版本")
    val tag = json.optString("tag_name")
    val version = tag.takeIf { it.startsWith("v") }?.let(StableVersion::parse)
        ?: throw UpdateFailure("无法识别最新版本号，请到仓库查看")
    // Derive navigation from the known repository, rather than trusting an API-supplied URL.
    return GitHubRelease(version.toString(), json.optString("name").takeUnless { it.isBlank() || it == "null" } ?: tag,
        json.optString("body").takeUnless { it == "null" }.orEmpty(),
        json.optString("published_at").takeUnless { it == "null" }.orEmpty(), "$KudoSourceUrl/releases/tag/$tag",
        releaseApk(json, tag, version.toString()))
}

private fun releaseApk(json: JSONObject, tag: String, version: String): GitHubApk? {
    val name = "kodo_${version}_release_arm64-v8a.apk"
    val url = "$KudoSourceUrl/releases/download/$tag/$name"
    val assets = json.optJSONArray("assets") ?: return null
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        if (asset.optString("name") != name || asset.optString("state") != "uploaded" ||
            asset.optLong("size") <= 0 || asset.optString("browser_download_url") != url) continue
        val digest = asset.optString("digest").takeIf { it.matches(Regex("sha256:[0-9a-fA-F]{64}")) }
            ?.substringAfter(':')?.lowercase()
        return GitHubApk(name, url, asset.optLong("size"), digest)
    }
    return null
}

private suspend fun Call.awaitPublicResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
    })
}
