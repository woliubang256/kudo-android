package com.kudo.android.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage

@Composable
internal fun MiniAppRuntimeTopBar(info: MiniAppInfo?, title: String, more: () -> Unit, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .heightIn(min = 56.dp).padding(horizontal = 12.dp).testTag("mini-app-title-bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SubcomposeAsyncImage(info?.iconUrl, null, Modifier.size(32.dp).clip(RoundedCornerShape(7.dp))
            .semantics { contentDescription = "小程序图标" },
            loading = { Icon(Icons.Rounded.Apps, null, Modifier.padding(4.dp)) },
            error = { Icon(Icons.Rounded.Apps, null, Modifier.padding(4.dp)) })
        Text(info?.name ?: title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        MiniAppCapsule(more, close)
    }
}
