package com.kudo.android.ui.topic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kudo.android.data.*
import com.kudo.android.ui.components.Avatar
import com.kudo.android.ui.components.AvatarPresentation

@Composable
internal fun TopicSystemAction(post: Post, enabled: Boolean, user: (String) -> Unit) {
    val icon = when (post.actionCode.substringBefore('.')) {
        "pinned", "pinned_globally" -> Icons.Rounded.PushPin
        "closed", "autoclosed" -> if (post.actionCode.endsWith(".disabled")) Icons.Rounded.LockOpen else Icons.Rounded.Lock
        "archived" -> Icons.Rounded.Archive
        "banner" -> Icons.Rounded.Campaign
        "visible" -> if (post.actionCode.endsWith(".disabled")) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility
        else -> Icons.Rounded.Info
    }
    PostReadingContent(post.number, enabled) {
        Box(Modifier.fillMaxWidth().testTag("post-${post.number}")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag("post-system-action-${post.number}"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Avatar(post.author, 22.dp, presentation = AvatarPresentation.PLAIN,
                onClick = if (enabled && post.author.username.isNotBlank()) ({ user(post.author.username) }) else null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(post.author.displayName, Modifier.clickable(enabled = enabled && post.author.username.isNotBlank()) { user(post.author.username) },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(postActionDescription(post.actionCode) + post.actionWho.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(relativeTime(post.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
    }
}
