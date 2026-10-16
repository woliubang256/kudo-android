package com.kudo.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import com.kudo.android.ui.theme.LogoYellow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TrustScreen(member: Member, repository: ConnectRepository, back: () -> Unit, website: () -> Unit) {
    val scroll = rememberLazyListState()
    var report by remember(member.id) { mutableStateOf<TrustReport?>(null) }
    var error by remember(member.id) { mutableStateOf<Throwable?>(null) }
    var loading by remember(member.id) { mutableStateOf(true) }
    var refresh by remember(member.id) { mutableIntStateOf(0) }
    LaunchedEffect(member.id, refresh) {
        loading = true; error = null
        try { report = withContext(Dispatchers.IO) { repository.load() } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e }
        finally { loading = false }
    }
    MaterialPageScaffold(header = {
        AccountPageTopBar("等级要求", back) {
            CommunityIconButton(Icons.Rounded.Refresh, "刷新等级数据", { refresh++ })
            CommunityIconButton(Icons.Rounded.Language, "打开等级网页", website)
        }
    }) { padding ->
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().quickScrollTarget(scroll), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { Text("当前等级 Lv.${member.trustLevel}", fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold) }
            if (loading) item { LoadingRow() }
            error?.let { item {
                CompositionLocalProvider(LocalWebVerification provides null) {
                    ErrorState(it, { refresh++ }, website)
                }
            } }
            if (error != null) item { CommunityButton("在应用内网页查看", website) }
            report?.let { data ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(data.title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        if (data.badge.isNotBlank()) Text(data.badge, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        if (data.subtitle.isNotBlank()) Text(data.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(data.metrics) { metric ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row {
                            Text(metric.label, Modifier.weight(1f), fontSize = 14.sp)
                            Icon(if (metric.met) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                                Modifier.padding(end = 6.dp).size(17.dp), tint = if (metric.met) Color(0xFF54A16A) else MaterialTheme.colorScheme.outline)
                        }
                        Text(metric.value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        metric.progress?.let { progress ->
                            Box(Modifier.fillMaxWidth().height(5.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))) {
                                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(LogoYellow, RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                }
                if (data.status.isNotBlank()) item { Text(data.status, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                if (data.hint.isNotBlank()) item { Text(data.hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item { Text("数据来自 connect.linux.do", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}
