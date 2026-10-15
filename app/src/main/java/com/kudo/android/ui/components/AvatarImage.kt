package com.kudo.android.ui.components

import android.graphics.drawable.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.asDrawable
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest

@Composable
internal fun AvatarImage(
    url: String?,
    pixels: Int,
    animate: Boolean,
    modifier: Modifier = Modifier,
    cacheKey: String? = null,
    fallbackUrl: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    onLoaded: (Boolean) -> Unit = {},
    onAspectRatio: (Float) -> Unit = {},
    placeholder: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var primaryFailed by remember(url, fallbackUrl) { mutableStateOf(false) }
    val source = if (primaryFailed) fallbackUrl else url ?: fallbackUrl
    var loaded by remember(source) { mutableStateOf(false) }
    var animation by remember(source) { mutableStateOf<Animatable?>(null) }
    val request = remember(context, source, pixels, cacheKey) {
        source?.let {
            val stableKey = cacheKey?.let { key -> "$key@$pixels" }
            ImageRequest.Builder(context).data(it).size(pixels).apply {
                if (stableKey != null) {
                    memoryCacheKey(stableKey)
                    diskCacheKey(stableKey)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    networkCachePolicy(CachePolicy.ENABLED)
                }
            }.build()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!loaded) placeholder()
        if (request != null) AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = contentScale,
            onSuccess = {
                loaded = true
                val image = it.result.image
                if (image.width > 0 && image.height > 0) onAspectRatio(image.width.toFloat() / image.height)
                onLoaded(true)
                val current = it.result.image.asDrawable(resources) as? Animatable
                animation = current
                // Loading can finish after the lifecycle has already paused its recomposer.
                if (!animate || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) current?.stop()
            },
            onError = {
                loaded = false
                onLoaded(false)
                if (!primaryFailed && fallbackUrl != null && fallbackUrl != source) primaryFailed = true
            })
    }
    LifecycleResumeEffect(animation, animate) {
        val current = animation
        if (animate) { if (current?.isRunning == false) current.start() } else current?.stop()
        onPauseOrDispose { current?.stop() }
    }
}
