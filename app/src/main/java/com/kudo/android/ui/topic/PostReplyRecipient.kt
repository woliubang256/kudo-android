package com.kudo.android.ui.topic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kudo.android.data.Member
import com.kudo.android.data.Post
import com.kudo.android.ui.components.Avatar
import com.kudo.android.ui.components.AvatarPresentation

@Composable
internal fun PostReplyRecipient(post: Post, posts: List<Post>, originalAuthor: Member?, enabled: Boolean,
    navigate: (Int) -> Unit, modifier: Modifier = Modifier) {
    val number = post.replyTo?.takeIf { it > 0 && it < post.number } ?: return
    val author = post.replyToUser ?: posts.firstOrNull { it.number == number && !it.isSystemAction }?.author
        ?: originalAuthor.takeIf { number == 1 }
    val description = author?.let { "回复${it.displayName}，第 $number 楼" } ?: "回复第 $number 楼"
    Row(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看第 $number 楼") { navigate(number) }
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(horizontal = 4.dp).testTag("post-reply-recipient-${post.number}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
        Icon(Icons.AutoMirrored.Rounded.Reply, null, Modifier.size(16.dp).scale(scaleX = -1f, scaleY = 1f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (author != null) Avatar(author, 20.dp, presentation = AvatarPresentation.PUBLIC)
        Text(author?.displayName ?: "#$number", Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
