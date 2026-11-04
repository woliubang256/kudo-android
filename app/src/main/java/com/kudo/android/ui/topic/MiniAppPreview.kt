package com.kudo.android.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.kudo.android.data.CommunityRepository
import com.kudo.android.data.MiniAppEntry
import com.kudo.android.data.MiniAppPreviewCache
import com.kudo.android.data.miniAppPreview
import com.kudo.android.ui.components.AppSquareIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

internal val LocalMiniAppPreviews = staticCompositionLocalOf<MiniAppPreviewCache?> { null }

@Composable
internal fun MiniAppPreviewEnvironment(repository: CommunityRepository, accountId: Int?, content: @Composable () -> Unit) {
    val parentScope = rememberCoroutineScope()
    val cache = remember(repository, accountId) {
        MiniAppPreviewCache(CoroutineScope(parentScope.coroutineContext + Dispatchers.IO), repository::miniAppPreview)
    }
    DisposableEffect(cache) { onDispose { cache.close() } }
    CompositionLocalProvider(LocalMiniAppPreviews provides cache, content = content)
}

@Composable
internal fun MiniAppPreview(id: String, url: String, open: (String) -> Unit) {
    val cache = LocalMiniAppPreviews.current
    var entry by remember(id, cache) { mutableStateOf<MiniAppEntry?>(null) }
    LaunchedEffect(id, cache) { entry = cache?.resolve(id) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(role = Role.Button) { open(url) }.testTag("mini-app-card").padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SubcomposeAsyncImage(entry?.iconUrl, null,
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).testTag("mini-app-card-icon"),
            contentScale = ContentScale.Fit,
            loading = { Icon(AppSquareIcon, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            error = { Icon(AppSquareIcon, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
        Text(entry?.name ?: "小程序", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
