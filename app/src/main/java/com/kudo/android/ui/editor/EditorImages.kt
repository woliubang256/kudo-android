package com.kudo.android.ui.editor

import android.content.Context
import android.net.Uri
import com.kudo.android.data.CommunityRepository

internal suspend fun uploadEditorImage(repository: CommunityRepository, context: Context, uri: Uri): String {
    val resolver = context.applicationContext.contentResolver
    val type = resolver.getType(uri) ?: throw IllegalArgumentException("无法识别图片格式")
    val extension = when (type) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> throw IllegalArgumentException("请选择 JPG、PNG、WebP 或 GIF 图片") }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count: Int
        while (input.read(buffer).also { count = it } != -1) { require(output.size() + count <= 10 * 1024 * 1024) { "图片不能超过 10 MB" }; output.write(buffer, 0, count) }
        output.toByteArray()
    } ?: throw IllegalArgumentException("无法读取图片")
    return repository.upload(bytes, type, "kudo-${System.currentTimeMillis()}.$extension")
}
