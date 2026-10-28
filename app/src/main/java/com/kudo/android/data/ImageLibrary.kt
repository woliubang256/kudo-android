package com.kudo.android.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val DEFAULT_IMAGE_FOLDER = "default"

data class ImageFolder(val id: String, val name: String)
data class ImageSource(val topicId: Int, val postNumber: Int = 1, val title: String = "") {
    val url get() = "https://linux.do/t/topic/$topicId/$postNumber"
}
data class SavedImage(
    val id: String = UUID.randomUUID().toString(),
    val folderId: String = DEFAULT_IMAGE_FOLDER,
    val originalUrl: String,
    val thumbnailUrl: String = originalUrl,
    val alt: String = "",
    val filename: String = "",
    val ratio: Float? = null,
    val source: ImageSource? = null,
    val savedAt: Long = System.currentTimeMillis(),
)
data class ImageLibrary(
    val folders: List<ImageFolder> = listOf(ImageFolder(DEFAULT_IMAGE_FOLDER, "默认收藏")),
    val images: List<SavedImage> = emptyList(),
) {
    fun addFolder(name: String, id: String = UUID.randomUUID().toString()): ImageLibrary {
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= 40) { "目录名称需为 1～40 个字符" }
        require(folders.none { it.name.equals(label, ignoreCase = true) }) { "已有同名目录" }
        require(id.isNotBlank() && folders.none { it.id == id })
        return copy(folders = folders + ImageFolder(id, label))
    }

    fun renameFolder(id: String, name: String): ImageLibrary {
        require(id != DEFAULT_IMAGE_FOLDER && folders.any { it.id == id }) { "默认目录不能重命名" }
        val renamed = copy(folders = folders.filterNot { it.id == id }).addFolder(name, id).folders.last()
        return copy(folders = folders.map { if (it.id == id) renamed else it })
    }

    fun deleteFolder(id: String): ImageLibrary {
        require(id != DEFAULT_IMAGE_FOLDER) { "默认目录不能删除" }
        return copy(folders = folders.filterNot { it.id == id },
            images = images.map { if (it.folderId == id) it.copy(folderId = DEFAULT_IMAGE_FOLDER) else it }
                .distinctBy { it.folderId to it.originalUrl })
    }

    fun save(image: SavedImage): ImageLibrary {
        require(folders.any { it.id == image.folderId }) { "目录已不存在，请重新选择" }
        require(validLibraryImageUrl(image.originalUrl)) { "图片地址无效，暂时无法收藏" }
        if (images.any { it.folderId == image.folderId && it.originalUrl == image.originalUrl }) return this
        return copy(images = listOf(image.copy(thumbnailUrl = image.thumbnailUrl.takeIf(::validLibraryImageUrl) ?: image.originalUrl,
            source = image.source?.takeIf { it.topicId > 0 && it.postNumber > 0 })) + images)
    }

    fun removeImage(id: String) = copy(images = images.filterNot { it.id == id })
}

fun validLibraryImageUrl(url: String): Boolean = url.none { it.isISOControl() } &&
    url.toHttpUrlOrNull()?.let { it.username.isEmpty() && it.password.isEmpty() } == true

fun encodeImageLibrary(library: ImageLibrary): String = JSONObject().put("version", 1)
    .put("folders", JSONArray().apply { library.folders.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
    .put("images", JSONArray().apply {
        library.images.forEach { image -> put(JSONObject().put("id", image.id).put("folder", image.folderId)
            .put("original", image.originalUrl).put("thumbnail", image.thumbnailUrl).put("alt", image.alt)
            .put("filename", image.filename).put("ratio", image.ratio).put("savedAt", image.savedAt)
            .apply { image.source?.let { put("source", JSONObject().put("topic", it.topicId).put("post", it.postNumber).put("title", it.title)) } }) }
    }).toString()

fun decodeImageLibrary(raw: String?): ImageLibrary {
    if (raw == null) return ImageLibrary()
    val json = JSONObject(raw)
    require(json.getInt("version") == 1) { "图库数据版本暂不支持，请更新应用" }
    val folders = json.getJSONArray("folders")
    var library = ImageLibrary()
    for (index in 0 until folders.length()) {
        val folder = folders.optJSONObject(index) ?: continue
        val id = folder.optString("id")
        if (id != DEFAULT_IMAGE_FOLDER) library = runCatching { library.addFolder(folder.getString("name"), id) }.getOrDefault(library)
    }
    val images = json.getJSONArray("images")
    val seen = mutableSetOf<Pair<String, String>>()
    val ids = mutableSetOf<String>()
    val restored = buildList {
        for (index in 0 until images.length()) {
            val image = images.optJSONObject(index) ?: continue
            val original = image.optString("original")
            val id = image.optString("id")
            val folder = image.optString("folder").takeIf { candidate -> library.folders.any { it.id == candidate } } ?: DEFAULT_IMAGE_FOLDER
            if (!validLibraryImageUrl(original) || id.isBlank() || id in ids || (folder to original) in seen) continue
            ids.add(id); seen.add(folder to original)
            val source = image.optJSONObject("source")?.let {
                ImageSource(it.optInt("topic"), it.optInt("post"), it.optString("title")).takeIf { value -> value.topicId > 0 && value.postNumber > 0 }
            }
            add(SavedImage(id, folder, original, image.optString("thumbnail").takeIf(::validLibraryImageUrl) ?: original,
                image.optString("alt"), image.optString("filename"), image.optDouble("ratio").toFloat().takeIf { it.isFinite() && it > 0 },
                source, image.optLong("savedAt")))
        }
    }
    return library.copy(images = restored)
}
