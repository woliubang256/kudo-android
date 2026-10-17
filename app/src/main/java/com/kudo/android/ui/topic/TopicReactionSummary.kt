package com.kudo.android.ui.topic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.Post
import com.kudo.android.data.popularReactions

@Composable
internal fun TopicReactionSummary(post: Post, enabled: Boolean, modifier: Modifier = Modifier) {
    val popular = post.popularReactions()
    if (post.reactionCount <= 0 || popular.isEmpty()) return
    val open = LocalReactionHistory.current
    Row(modifier.clip(CircleShape).clickable(enabled = enabled, role = Role.Button) { open(post) }
        .heightIn(min = 48.dp).testTag("post-reaction-summary-${post.number}")
        .semantics(mergeDescendants = true) { contentDescription = "查看第 ${post.number} 楼的点赞与回应历史，共 ${post.reactionCount} 人" }
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.width(18.dp + 12.dp * (popular.size - 1)).height(18.dp)) {
            popular.forEachIndexed { index, reaction ->
                SiteEmoji(reaction.id, Modifier.offset(x = 12.dp * index).size(18.dp).testTag("post-reaction-${post.number}-${reaction.id}"))
            }
        }
        BasicText(post.reactionCount.toString(), Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), maxLines = 1,
            overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp, stepSize = .5.sp))
    }
}
