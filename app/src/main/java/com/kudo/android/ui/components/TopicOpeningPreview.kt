package com.kudo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kudo.android.data.Topic
import com.kudo.android.ui.theme.LocalDisplaySettings

@Composable
internal fun TopicOpeningPreview(topic: Topic, back: () -> Unit) {
    val blurred = LocalDisplaySettings.current.blurNsfwImages && topic.hasNsfwTag()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .testTag("topic-opening-preview")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            topic.author?.let { Avatar(it, 32.dp, presentation = AvatarPresentation.PLAIN) }
            Text(topic.author?.displayName ?: "话题详情", style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TopicTitleText(topic.title, style = MaterialTheme.typography.headlineSmall)
            if (topic.excerpt.isNotBlank()) Text(topic.excerpt, style = MaterialTheme.typography.bodyMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            mediaUrl(topic.image)?.let { url ->
                AsyncImage(url, if (blurred) "帖子封面摘要，已模糊" else "帖子封面摘要",
                    Modifier.fillMaxWidth().aspectRatio(1.9f).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (blurred) Modifier.blur(14.dp) else Modifier), contentScale = ContentScale.Crop)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(1f, .9f, .65f).forEach { width ->
                    Box(Modifier.fillMaxWidth(width).height(12.dp).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)))
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Text("正在加载正文…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
