package com.kudo.android.ui.account

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kudo.android.BuildConfig
import com.kudo.android.data.GitHubRelease
import com.kudo.android.data.KudoSourceUrl
import com.kudo.android.ui.components.*
import com.kudo.android.ui.components.liquid.LiquidModal
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.CancellationException

@Composable
internal fun UpdateSheet(visibility: MutableTransitionState<Boolean>, check: suspend () -> GitHubRelease,
    backdrop: Backdrop, external: (String) -> Unit, dismiss: () -> Unit) {
    UpdateSheetContent(visibility, check, backdrop, external, dismiss, null)
}

@Composable
internal fun UpdateSheet(visibility: MutableTransitionState<Boolean>, release: GitHubRelease,
    backdrop: Backdrop, external: (String) -> Unit, dismiss: () -> Unit) {
    UpdateSheetContent(visibility, { release }, backdrop, external, dismiss, release)
}

@Composable
private fun UpdateSheetContent(visibility: MutableTransitionState<Boolean>, check: suspend () -> GitHubRelease,
    backdrop: Backdrop, external: (String) -> Unit, dismiss: () -> Unit, initialRelease: GitHubRelease?) {
    var release by remember(initialRelease) { mutableStateOf(initialRelease) }
    var newer by remember(initialRelease) { mutableStateOf(initialRelease?.isNewerThan(BuildConfig.VERSION_NAME) ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val downloadActions = LocalUpdateDownloadActions.current
    val latestCheck by rememberUpdatedState(check)
    LaunchedEffect(visibility.targetState, retry) {
        if (!visibility.targetState || initialRelease != null) return@LaunchedEffect
        loading = true; error = null; release = null
        try {
            val value = latestCheck()
            newer = value.isNewerThan(BuildConfig.VERSION_NAME)
            release = value
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "检查失败，请稍后重试" }
        finally { loading = false }
    }
    LiquidModal(visibility, "检查更新", false, backdrop, LocalDisplaySettings.current.glass, dismiss,
        maxDialogHeightFraction = .8f, showTitle = release == null,
        scrollContent = release == null, showActions = release != null, actions = {
            release?.let { value ->
                Box(Modifier.fillMaxWidth()) {
                    if (newer && downloadActions != null) downloadActions(value)
                    else if (newer) CommunityButton("前往 GitHub 下载", { external(value.url) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(50))
                    else ModalLink("在 GitHub 查看此版本") { external(value.url) }
                }
            }
        }) {
        if (loading) {
            LoadingRow()
            Text("正在检查 GitHub 版本…", style = MaterialTheme.typography.bodyMedium)
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            CommunityButton("重试", { retry++ }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(50))
            ModalLink("到 GitHub 查看版本") { external("$KudoSourceUrl/releases") }
        }
        release?.let { value ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (newer) "发现新版本 ${value.version}" else "当前已是最新版本",
                    style = MaterialTheme.typography.titleLarge)
                Text("当前 ${BuildConfig.VERSION_NAME} · 最新 ${value.version}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (value.publishedAt.isNotBlank()) Text("发布于 ${value.publishedAt.take(10)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ReleaseNotes(value.notes.ifBlank { "此版本暂未提供更新日志。" }, external,
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
        }
    }
}

/** Release bodies use Markdown headings, bullet lists, and links; long notes remain selectable and scrollable. */
@Composable
private fun ReleaseNotes(body: String, external: (String) -> Unit, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            body.lines().forEach { raw ->
                val heading = raw.startsWith("#")
                val line = raw.trim().removePrefix("### ").removePrefix("## ").removePrefix("# ")
                    .replace(Regex("^[-*] "), "• ")
                if (line.isNotBlank()) Text(releaseLine(line, external),
                    style = if (heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = if (heading) 8.dp else 0.dp))
            }
        }
    }
}

private fun releaseLine(line: String, external: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("\\[([^]]+)]\\((https?://[^\\s)]+)\\)|\\*\\*([^*]+)\\*\\*")
    var cursor = 0
    pattern.findAll(line).forEach { match ->
        append(line.substring(cursor, match.range.first))
        if (match.groupValues[2].isNotEmpty()) {
            val url = match.groupValues[2]
            withLink(LinkAnnotation.Clickable(url, linkInteractionListener = { external(url) })) { append(match.groupValues[1]) }
        } else withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[3]) }
        cursor = match.range.last + 1
    }
    append(line.substring(cursor))
}
