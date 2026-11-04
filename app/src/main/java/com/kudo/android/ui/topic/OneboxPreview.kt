package com.kudo.android.ui.topic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kudo.android.ui.LinuxDoLink
import com.kudo.android.ui.components.mediaUrl
import com.kudo.android.ui.web.MiniAppLocation
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element

internal fun parseOnebox(element: Element): ArticleBlock.Embed? {
    val heading = element.selectFirst(".onebox-body h1 a[href], .onebox-body h2 a[href], .onebox-body h3 a[href], .onebox-body h4 a[href]")
        ?: element.selectFirst("h1 a[href], h2 a[href], h3 a[href], h4 a[href]")
    val source = element.selectFirst("header.source a[href], header a[href]")
    val first = element.selectFirst("a[href]")
    val url = sequenceOf(element.attr("data-onebox-src"), heading?.attr("href"), source?.attr("href"), first?.attr("href"))
        .filterNotNull().mapNotNull(LinuxDoLink::webUrl).firstOrNull() ?: return null
    val title = heading?.text()?.takeIf(String::isNotBlank) ?: source?.text()?.takeIf(String::isNotBlank)
        ?: first?.text()?.takeIf(String::isNotBlank) ?: url
    val description = element.select(".onebox-body p").map(Element::text).filter(String::isNotBlank).joinToString("\n")
    val image = element.selectFirst(".onebox-body img.thumbnail, .onebox-body img:not(.emoji):not(.avatar), img.thumbnail")
        ?.attr("src")?.let(::mediaUrl)
    // Preserve the compact fallback and its existing contract when there is no preview metadata.
    val site = if (heading != null || description.isNotBlank() || image != null) {
        source?.text()?.takeIf(String::isNotBlank) ?: url.toHttpUrlOrNull()?.host.orEmpty()
    } else ""
    return ArticleBlock.Embed(url, title, description, image, site)
}

@Composable
internal fun OneboxPreview(block: ArticleBlock.Embed, open: (String) -> Unit) {
    val appId = remember(block.url) { MiniAppLocation.parse(block.url)?.id }
    if (appId != null) {
        MiniAppPreview(appId, block.url, open)
        return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(role = Role.Button) { open(block.url) }.padding(14.dp).testTag("onebox-preview"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (block.site.isNotBlank()) Text(block.site, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var failed by remember(block.image) { mutableStateOf(false) }
        if (block.image != null && !failed) AsyncImage(block.image, null,
            Modifier.fillMaxWidth().heightIn(max = 140.dp).aspectRatio(2f).clip(RoundedCornerShape(6.dp)).testTag("onebox-thumbnail"),
            contentScale = ContentScale.Crop, onError = { failed = true })
        Text(block.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (block.description.isNotBlank()) Text(block.description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}
