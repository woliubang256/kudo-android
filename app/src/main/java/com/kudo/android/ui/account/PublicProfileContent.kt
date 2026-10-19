package com.kudo.android.ui.account

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.kudo.android.data.*
import com.kudo.android.ui.components.*

private fun LazyListScope.profileStatistics(summary: ProfileSummary) {
    item("statistics") {
        ProfileSectionTitle("阅读与参与")
        val labels = listOf("posts_read_count" to "已读帖子", "topics_entered" to "浏览话题", "days_visited" to "访问天数",
            "likes_given" to "送出赞", "time_read" to "阅读时长", "recent_time_read" to "近期阅读")
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
            val columns = if (maxWidth >= 280.dp) 3 else 2
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                labels.chunked(columns).forEach { chunk -> Row(Modifier.fillMaxWidth()) {
                    chunk.forEach { (key, label) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val value = summary.counts[key]
                        Text(if (value == null) "—" else if (key.endsWith("time_read") || key == "time_read") "${value / 3600}小时${value % 3600 / 60}分" else compactNumber(value),
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                } }
            }
        }
    }
}

internal fun LazyListScope.publicSummary(summary: ProfileSummary, open: (String) -> Unit, badge: (UserBadge) -> Unit) {
    if (summary.topics.isNotEmpty()) {
        item("popular-topics-title") { ProfileSectionTitle("热门话题") }
        items(summary.topics, key = { "top-${it.key}" }) { ProfileEntryRow(it, open) }
    }
    if (summary.replies.isNotEmpty()) {
        item("popular-replies-title") { ProfileSectionTitle("热门回复") }
        items(summary.replies, key = { "reply-${it.key}" }) { ProfileEntryRow(it, open) }
    }
    if (summary.links.isNotEmpty()) {
        item("popular-links-title") { ProfileSectionTitle("热门链接") }
        items(summary.links.withIndex().toList(), key = { "link-${it.index}" }) { (_, link) ->
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface).clickable { open(link.url) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Link, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(link.title, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                link.clicks?.let { Text("${compactNumber(it)}次", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    listOf("most_replied_to_users" to "经常回复的人", "most_liked_by_users" to "经常获赞于", "most_liked_users" to "经常赞的人").forEach { (key, title) ->
        val users = summary.users[key].orEmpty()
        if (users.isNotEmpty()) item(key) {
            ProfileSectionTitle(title)
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(users, key = { it.member.username }) { value ->
                    Column(Modifier.width(68.dp).clickable(role = Role.Button) { open("https://linux.do/u/${android.net.Uri.encode(value.member.username)}") },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Avatar(value.member, 46.dp)
                        Text(value.member.displayName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        value.count?.let { Text("${compactNumber(it)}次", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    if (summary.categories.isNotEmpty()) {
        item("categories-title") { ProfileSectionTitle("常驻分类") }
        items(summary.categories, key = { "category-${it.id}" }) { category ->
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface).clickable { open("https://linux.do/c/${category.id}") }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CategoryInline(category) }
                Text("${compactNumber(category.count)}话题", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (summary.badges.isNotEmpty()) item("badges") {
        ProfileSectionTitle("获得的徽章")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(summary.badges, key = { it.id }) { value ->
                val color = when (value.type) { 1 -> Color(0xFFD4A738); 2 -> Color(0xFF9AA5B2); else -> Color(0xFFC28B61) }
                val context = LocalContext.current
                Column(Modifier.width(72.dp).clickable(role = Role.Button) { badge(value) }, horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(48.dp).border(1.5.dp, color, CircleShape).padding(10.dp), contentAlignment = Alignment.Center) {
                        if (value.imageUrl != null) AsyncImage(mediaUrl(value.imageUrl), null, Modifier.fillMaxSize())
                        else Icon(remember(value.icon) { BadgeIcons.get(context, value.icon) }, null, Modifier.fillMaxSize(), tint = color)
                    }
                    Text(value.name, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    profileStatistics(summary)
}

@Composable
internal fun ProfileSectionTitle(title: String) {
    Text(title, Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun ProfileEntryRow(entry: ProfileEntry, open: (String) -> Unit) {
    val category = entry.category ?: LocalTopicPresentation.current.categories.firstOrNull { it.id == entry.categoryId }
    val transition = topicTransitionAnchor(entry.topicId, 10.dp) { open(entry.url) }
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().then(transition.modifier).clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surface).clickable(role = Role.Button, onClick = transition.open)
        .testTag("profile-entry-${entry.key}").padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                TopicTitleText(entry.title, Modifier.fillMaxWidth(), fontSize = 15.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.tags.isNotEmpty()) TopicTags(entry.tags)
                if (entry.excerpt.isNotBlank()) Text(entry.excerpt, fontSize = 13.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (entry.image.isNotBlank()) AsyncImage(mediaUrl(entry.image), "${entry.title}封面",
                Modifier.size(width = 78.dp, height = 58.dp).clip(RoundedCornerShape(7.dp)).testTag("profile-entry-image-${entry.key}"),
                contentScale = ContentScale.Crop)
        }
        if (category != null || entry.annotation.isNotBlank() || entry.time.isNotBlank() || entry.number?.let { it > 1 } == true || entry.count != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).testTag("profile-entry-category-${entry.key}")) {
                    if (category != null) {
                        CategoryInline(category)
                    } else if (entry.annotation.isNotBlank()) {
                        Text(entry.annotation, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                entry.number?.takeIf { it > 1 }?.let { Text("#$it", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline) }
                entry.count?.let {
                    Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(compactNumber(it), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry.time.isNotBlank()) Text(relativeTime(entry.time).ifBlank { entry.time.take(10) }, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
