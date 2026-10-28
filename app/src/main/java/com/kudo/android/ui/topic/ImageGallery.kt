package com.kudo.android.ui.topic

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.kudo.android.data.ImageSource

internal val LocalImageGallery = staticCompositionLocalOf<ImageGalleryState?> { null }

internal fun ArticleBlock.Image.saveImage(): List<Any> = arrayListOf(
    url, alt, ratio ?: 0f, originalUrl, filename, dimensions, sizeLabel, displayWidth ?: 0f,
)

internal fun restoreGalleryImage(values: List<Any>) = ArticleBlock.Image(
    values[0] as String, values[1] as String, (values[2] as Float).takeIf { it > 0 },
    values[3] as String, values[4] as String, values[5] as String, values[6] as String,
    (values.getOrNull(7) as? Float)?.takeIf { it > 0 },
)

internal val GalleryImageSaver = listSaver<ArticleBlock.Image, Any>(
    save = { it.saveImage() }, restore = { restoreGalleryImage(it) },
)

internal fun sameGalleryImage(first: ArticleBlock.Image, second: ArticleBlock.Image): Boolean {
    val left = setOf(first.url, first.originalUrl)
    val right = setOf(second.url, second.originalUrl)
    if (left.any { it in right }) return true
    fun uploadKey(url: String): String? {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return null
        if (!path.contains("/uploads/")) return null
        return path.substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("_\\d+_\\d+x\\d+.*$"), "").takeIf { it.length >= 20 }
    }
    return left.mapNotNull(::uploadKey).any { key -> right.any { uploadKey(it) == key } }
}

@Stable
internal class GallerySession(
    val origin: String, images: List<ArticleBlock.Image>, index: Int,
    val topicId: Int? = null, val restored: Boolean = false, val source: ImageSource? = null,
) {
    val openingImage = images[index]
    var images by mutableStateOf(images)
        private set
    var transforms by mutableStateOf(images.map { GalleryTransformState(it.ratio) })
        private set
    var index by mutableIntStateOf(index.coerceIn(images.indices))
        private set
    var closing by mutableStateOf(false)
        internal set
    var albumLoaded by mutableStateOf(topicId == null)
        internal set
    var loading by mutableStateOf(false)
        internal set
    var error by mutableStateOf(false)
        internal set
    var retry by mutableIntStateOf(0)
        private set
    val current get() = images[index]

    fun select(index: Int) { if (!closing && index in images.indices) this.index = index }
    fun retryAlbum() { if (!loading && !closing) retry++ }

    internal fun replaceAlbum(album: List<ArticleBlock.Image>) {
        val displayed = current
        val displayedTransform = transforms[index]
        val matched = album.indexOfFirst { sameGalleryImage(it, displayed) }
        // A separately configured topic cover may not occur in the post body.
        val replacement = if (matched < 0) listOf(displayed) + album else album
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            images = replacement
            transforms = replacement.mapIndexed { page, image ->
                if (page == matched.coerceAtLeast(0)) displayedTransform else GalleryTransformState(image.ratio)
            }
            index = matched.coerceAtLeast(0)
            albumLoaded = true
        }
    }
}

internal data class GalleryAnchorSnapshot(
    val bounds: Rect, val visibleBounds: Rect, val contentScale: ContentScale,
    val cornerRadius: Float, val painter: Painter?,
)

@Stable
internal class GalleryThumbnailAnchor {
    val requester = BringIntoViewRequester()
    var coordinates: LayoutCoordinates? = null
    var contentScale: ContentScale = ContentScale.Fit
    var cornerRadius = 0f
    var painter: Painter? = null

    fun snapshot(): GalleryAnchorSnapshot? {
        val layout = coordinates?.takeIf { it.isAttached } ?: return null
        val bounds = Rect(layout.positionInWindow(), Size(layout.size.width.toFloat(), layout.size.height.toFloat()))
        val visible = layout.boundsInWindow()
        return if (bounds.isEmpty || visible.isEmpty) null else GalleryAnchorSnapshot(bounds, visible, contentScale, cornerRadius, painter)
    }
}

internal fun interface GalleryOrigin {
    suspend fun reveal(image: ArticleBlock.Image, index: Int): GalleryThumbnailAnchor?
}

@Stable
internal class ImageGalleryState {
    var session by mutableStateOf<GallerySession?>(null)
        private set
    internal val origins = mutableMapOf<String, GalleryOrigin>()
    var openingAnchor: GalleryThumbnailAnchor? = null
        private set
    var openingSnapshot: GalleryAnchorSnapshot? = null
        private set
    var hiddenAnchor by mutableStateOf<GalleryThumbnailAnchor?>(null)
        internal set

    fun open(images: List<ArticleBlock.Image>, index: Int, origin: String,
             anchor: GalleryThumbnailAnchor? = null, topicId: Int? = null, source: ImageSource? = null) {
        if (session != null || images.isEmpty() || index !in images.indices) return
        openingAnchor = anchor
        openingSnapshot = anchor?.snapshot()
        session = GallerySession(origin, images, index, topicId, source = source ?: topicId?.let { ImageSource(it) })
    }

    suspend fun loadAlbum(target: GallerySession, load: suspend (Int) -> List<ArticleBlock.Image>) {
        val topicId = target.topicId ?: return
        if (target.albumLoaded || target.loading || target.closing || session !== target) return
        target.loading = true
        target.error = false
        try {
            val album = load(topicId)
            if (session === target && !target.closing) target.replaceAlbum(album)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (session === target && !target.closing) target.error = true
        } finally { target.loading = false }
    }

    suspend fun dismiss(index: Int? = null, prepare: suspend () -> Unit = {}, animate: suspend (GalleryThumbnailAnchor?) -> Unit = {}) {
        val target = session ?: return
        if (target.closing) return
        index?.let(target::select)
        target.closing = true
        try {
            prepare()
            val anchor = try {
                withTimeoutOrNull(1_200) { origins[target.origin]?.reveal(target.current, target.index) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            animate(anchor)
        } finally { if (session === target) clear() }
    }

    fun clear() { session = null; openingAnchor = null; openingSnapshot = null; hiddenAnchor = null }

    companion object {
        @Suppress("UNCHECKED_CAST")
        val Saver = listSaver<ImageGalleryState, Any>(
            save = { state -> state.session?.let { active ->
                listOf(active.origin, active.index, active.topicId ?: 0,
                    ArrayList(active.images.map { ArrayList(it.saveImage()) }), active.albumLoaded,
                    active.source?.topicId ?: 0, active.source?.postNumber ?: 0, active.source?.title.orEmpty())
            }.orEmpty() },
            restore = { saved -> ImageGalleryState().apply {
                if (saved.isNotEmpty()) session = GallerySession(saved[0] as String,
                    (saved[3] as List<List<Any>>).map(::restoreGalleryImage), saved[1] as Int,
                    (saved[2] as Int).takeIf { it > 0 }, restored = true,
                    source = (saved.getOrNull(5) as? Int)?.takeIf { it > 0 }?.let {
                        ImageSource(it, (saved.getOrNull(6) as? Int)?.takeIf { number -> number > 0 } ?: 1, saved.getOrNull(7) as? String ?: "")
                    }).also { it.albumLoaded = saved[4] as Boolean }
            } },
        )
    }
}

@Composable
internal fun RegisterGalleryOrigin(key: String, reveal: suspend (ArticleBlock.Image, Int) -> GalleryThumbnailAnchor?) {
    val gallery = LocalImageGallery.current
    val current by rememberUpdatedState(reveal)
    DisposableEffect(gallery, key) {
        val origin = GalleryOrigin { image, index -> current(image, index) }
        gallery?.origins?.put(key, origin)
        onDispose { if (gallery?.origins?.get(key) === origin) gallery.origins.remove(key) }
    }
}

@Composable
fun ImageGalleryHost(
    loadAlbum: suspend (Int) -> List<ArticleBlock.Image>, openLink: (String) -> Unit,
    accountKey: Int? = null, content: @Composable () -> Unit,
) {
    val gallery = rememberSaveable(accountKey, saver = ImageGalleryState.Saver) { ImageGalleryState() }
    val active = gallery.session
    val currentLoad by rememberUpdatedState(loadAlbum)
    LaunchedEffect(active, active?.retry) { if (active != null) gallery.loadAlbum(active, currentLoad) }
    CompositionLocalProvider(LocalImageGallery provides gallery,
        LocalReadingBlocked provides (LocalReadingBlocked.current || active != null)) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if (active != null) Modifier.clearAndSetSemantics {} else Modifier)
                .focusProperties { onEnter = { if (active != null) cancelFocusChange() } }.focusGroup()) {
                content()
            }
            if (active != null) key(active) { ImageScreen(gallery, active, openLink) }
        }
    }
}
