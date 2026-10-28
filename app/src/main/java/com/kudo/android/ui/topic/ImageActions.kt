package com.kudo.android.ui.topic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.SingletonImageLoader
import com.kudo.android.KudoApplication
import com.kudo.android.ui.components.showKudoToast
import com.kudo.android.media.*
import com.kudo.android.data.ImageSource
import com.kudo.android.data.SavedImage
import com.kudo.android.ui.library.CollectImageSheet
import com.kudo.android.ui.library.LocalImageLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal enum class ImageAction { SHARE, SAVE, SAVE_ALL, COLLECT }
internal data class ImageActionTarget(val current: ArticleBlock.Image, val album: List<ArticleBlock.Image>, val source: ImageSource? = null)
internal class ImageExportServices(val downloader: ImageDownloader, val saver: ImageSaver, val share: (OriginalImageFile) -> Unit)
internal val LocalImageExportServices = staticCompositionLocalOf<ImageExportServices?> { null }

private val imageCapacityPattern = Regex("""^(\d+(?:\.\d+)?)\s*(bytes?|字节|b|[kmgtpe]i?b)$""", RegexOption.IGNORE_CASE)
private val imageCapacityUnits = listOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

private fun imageCapacityBytes(label: String): Long? {
    val match = imageCapacityPattern.matchEntire(label.replace('\u00a0', ' ').replace('\u202f', ' ').trim()) ?: return null
    val unit = match.groupValues[2].uppercase(Locale.ROOT).replace("I", "")
    val power = if (unit in listOf("BYTE", "BYTES", "字节")) 0 else imageCapacityUnits.indexOf(unit)
    if (power < 0) return null
    return runCatching {
        match.groupValues[1].toBigDecimal().multiply(BigDecimal.valueOf(1024).pow(power))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()?.takeIf { it > 0 }
}

private fun formatImageCapacity(bytes: Long): String {
    var power = 0
    var divisor = 1L
    while (power < imageCapacityUnits.lastIndex && bytes / divisor >= 1024) { divisor *= 1024; power++ }
    val value = BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(divisor), if (power == 0) 0 else 1, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
    return "$value ${imageCapacityUnits[power]}"
}

internal fun imageActionCapacityLabels(target: ImageActionTarget, capacities: Map<String, Long> = emptyMap()): Pair<String?, String?> {
    fun bytes(image: ArticleBlock.Image) = capacities[image.originalUrl]?.takeIf { it > 0 } ?: imageCapacityBytes(image.sizeLabel)
    val current = bytes(target.current)?.let(::formatImageCapacity)
    val sizes = target.album.map(::bytes)
    val total = if (sizes.isEmpty() || sizes.any { it == null }) null else runCatching {
        sizes.fold(0L) { sum, bytes -> Math.addExact(sum, requireNotNull(bytes)) }
    }.getOrNull()
    return current to total?.let { "约 ${formatImageCapacity(it)}" }
}

/** Deliberately uses flat text rows and a small radius, matching the provided Coolapk menu. */
@Composable
internal fun ImageActionMenu(target: ImageActionTarget, dismiss: () -> Unit, select: (ImageAction) -> Unit,
    capacities: Map<String, Long> = emptyMap(), collectEnabled: Boolean = false) {
    val (currentCapacity, totalCapacity) = remember(target, capacities) { imageActionCapacityLabels(target, capacities) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(horizontal = 32.dp).widthIn(max = 320.dp).fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()).padding(vertical = 8.dp).testTag("image-action-menu")) {
            val options = listOf(ImageAction.SHARE to "分享图片", ImageAction.SAVE to ("下载图片" + currentCapacity?.let { "（$it）" }.orEmpty())) +
                (if (target.album.size > 1) listOf(ImageAction.SAVE_ALL to ("下载全部" + totalCapacity?.let { "（$it）" }.orEmpty())) else emptyList()) +
                (if (collectEnabled) listOf(ImageAction.COLLECT to "收藏图片") else emptyList())
            options.forEach { (action, title) ->
                Box(Modifier.fillMaxWidth().clickable(role = Role.Button) { select(action) }
                    .heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
internal fun ImageActions(target: ImageActionTarget?, dismiss: () -> Unit, busyChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = LocalImageLibrary.current
    var collecting by remember(library) { mutableStateOf<SavedImage?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<ArticleBlock.Image>?>(null) }
    collecting?.let { CollectImageSheet(it) { collecting = null } }
    if (target == null && !busy && pending == null) return
    val injected = LocalImageExportServices.current
    val services = remember(context, injected) {
        injected ?: run {
            val cache = SingletonImageLoader.get(context).diskCache
            val downloader = ImageDownloader((context.applicationContext as KudoApplication).container.imageHttpClient,
                File(context.cacheDir, "image_exports"), cachedSize = { url ->
                    cache?.openSnapshot(url)?.use { cache.fileSystem.metadata(it.data).size }
                })
            ImageExportServices(downloader, ImageSaver(downloader, GalleryImageStore(context.applicationContext))) { image ->
                context.startActivity(Intent.createChooser(imageShareIntent(context, image), "分享图片")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
    }
    var capacities by remember(target) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(target, services) {
        val selected = target ?: return@LaunchedEffect
        val known = mutableMapOf<String, Long>()
        (listOf(selected.current) + selected.album).distinctBy { it.originalUrl }.forEach { image ->
            services.downloader.cachedCapacity(OriginalImage(image.originalUrl))?.let { known[image.originalUrl] = it }
        }
        capacities = known.toMap()
        if (known[selected.current.originalUrl] == null && imageCapacityBytes(selected.current.sizeLabel) == null) {
            services.downloader.capacity(OriginalImage(selected.current.originalUrl))?.let {
                capacities = known + (selected.current.originalUrl to it)
            }
        }
    }
    fun message(text: String) { showKudoToast(context, text) }
    fun save(images: List<ArticleBlock.Image>) {
        if (busy) return
        busy = true; busyChanged(true)
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val result = services.saver.save(images.map { OriginalImage(it.originalUrl, it.filename) })
                message(when {
                    result.saved == result.total -> if (result.total == 1) "图片已保存到相册" else "已保存 ${result.saved} 张图片到相册"
                    result.saved == 0 -> "保存失败，请检查网络或存储空间"
                    else -> "已保存 ${result.saved} 张，${result.total - result.saved} 张保存失败"
                })
            } finally { busy = false; busyChanged(false) }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val images = pending
        pending = null
        busyChanged(false)
        if (granted && images != null) save(images)
        else if (images != null) message("未获得存储权限，无法保存图片")
    }
    DisposableEffect(Unit) { onDispose { busyChanged(false) } }
    if (target != null) ImageActionMenu(target, dismiss, capacities = capacities, collectEnabled = library != null, select = { action ->
        dismiss()
        if (!busy && pending == null) {
            if (action == ImageAction.COLLECT) {
                collecting = SavedImage(originalUrl = target.current.originalUrl, thumbnailUrl = target.current.url,
                    alt = target.current.alt, filename = target.current.filename, ratio = target.current.ratio, source = target.source)
            } else if (action == ImageAction.SHARE) {
                busy = true; busyChanged(true)
                scope.launch(Dispatchers.Main.immediate) {
                    var file: OriginalImageFile? = null
                    try {
                        file = services.downloader.download(OriginalImage(target.current.originalUrl, target.current.filename))
                        services.share(file)
                        // Keep the cache for the receiving app; a later export removes files older than a day.
                        file = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message("分享失败，请检查图片是否能加载或稍后重试") }
                    finally { file?.file?.delete(); busy = false; busyChanged(false) }
                }
            } else {
                val images = if (action == ImageAction.SAVE_ALL) target.album else listOf(target.current)
                if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pending = images
                    busyChanged(true)
                    permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else save(images)
            }
        }
    })
}
