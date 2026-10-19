package com.kudo.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.Topic

@Composable
internal fun TopicOpeningPreview(topic: Topic, back: () -> Unit) {
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
            TopicOpeningSkeleton()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Text("正在加载正文…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TopicOpeningSkeleton() {
    val transition = rememberInfiniteTransition(label = "topic-opening-skeleton")
    val phase = transition.animateFloat(-.8f, 1.8f,
        infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "topic-opening-sweep")
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = .14f)
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf(listOf(1f, .94f, 1f, .68f), listOf(.96f, 1f, .9f, .76f), listOf(1f, .92f, .98f, .58f))
            .forEachIndexed { index, widths ->
                Canvas(Modifier.fillMaxWidth().height(88.dp).testTag("topic-opening-paragraph-$index")) {
                    val start = size.width * (phase.value - index * .12f)
                    val brush = Brush.linearGradient(listOf(base, highlight, base),
                        start = Offset(start, 0f), end = Offset(start + size.width * .65f, size.height))
                    widths.forEachIndexed { line, width ->
                        drawRoundRect(brush, Offset(0f, line * 24.dp.toPx()), Size(size.width * width, 16.dp.toPx()),
                            CornerRadius(4.dp.toPx()))
                    }
                }
            }
    }
}
