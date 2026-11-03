package com.kudo.android.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kudo.android.ui.components.CommunityButton
import com.kudo.android.ui.components.CommunityLoadingIndicator
import com.kudo.android.ui.theme.LogoYellow
import kotlin.math.roundToInt

@Composable
internal fun MiniAppLaunchContent(state: MiniAppLaunchState, retry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("mini-app-launch")
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.phase == MiniAppLaunchPhase.LOADING && state.progress == null)
                CommunityLoadingIndicator(Modifier.testTag("mini-app-launch-indicator"))
            val label = if (state.progress == null) state.stage else "${state.stage} ${(state.progress * 100).roundToInt()}%"
            Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.progress?.let { progress ->
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outlineVariant).progressSemantics(progress)
                    .testTag("mini-app-launch-progress")) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(LogoYellow))
                }
            }
            if (state.detail.isNotBlank() && state.detail != state.stage)
                Text(state.detail, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.phase == MiniAppLaunchPhase.ERROR && state.retryable) CommunityButton("重试", retry)
        }
    }
}
