package com.kudo.android.ui.account

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kudo.android.reporting.*
import com.kudo.android.ui.components.liquid.LiquidModal
import com.kudo.android.ui.components.liquid.LiquidModalAction
import com.kudo.android.ui.components.showKudoToast
import com.kudo.android.ui.theme.LocalDisplaySettings
import com.kudo.android.ui.web.*
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val ReportingExplanation = "仅收集排错所需的错误堆栈、应用版本及系统和设备信息，用于定位问题。不包含社区账号、Cookie、帖子或输入内容。可随时在设置中修改。"

@Composable
internal fun ReportingConsentPrompt(controller: ReportingController?) {
    if (controller == null) return
    val consent by controller.consent.collectAsStateWithLifecycle()
    val currentOwner by controller.promptOwner.collectAsStateWithLifecycle()
    val owner = remember(controller) { Any() }
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(controller, owner) {
        resumed = true
        onPauseOrDispose { resumed = false; controller.releasePrompt(owner) }
    }
    LaunchedEffect(resumed, consent, currentOwner) {
        if (resumed && consent == ReportingConsent.UNKNOWN) controller.acquirePrompt(owner)
    }
    val visibility = remember { MutableTransitionState(false) }
    SideEffect { visibility.targetState = resumed && currentOwner === owner && consent == ReportingConsent.UNKNOWN }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    fun choose(allow: Boolean) {
        if (saving || !visibility.targetState) return
        saving = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { controller.decide(owner, allow) }
            if (!saved) showKudoToast(context, "无法保存，请重试")
            else if (allow && !controller.active) showKudoToast(context, "已保存，错误上报暂未启动，将在下次启动重试")
            saving = false
        }
    }
    // PixelCopy samples the Activity; the dialog is in a different window and never samples itself.
    val glass = LocalDisplaySettings.current.glass
    val showing = visibility.currentState || visibility.targetState
    val backdrop = rememberMiniAppFloatingBackdrop(showing, glass)
    if (!showing) return
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, dismissOnClickOutside = false, dismissOnBackPress = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(window) { window.setDimAmount(0f); window.setWindowAnimations(0); onDispose {} }
        Box(Modifier.fillMaxSize().testTag("reporting-consent-window").semantics {
            floatingMaterial = glass; floatingSample = backdrop.ready
        }) {
            LiquidModal(visibility, "是否启用 Sentry 错误上报？", false, backdrop, glass, {}, actions = {
                LiquidModalAction("不允许", { choose(false) }, Modifier.weight(1f))
                LiquidModalAction("允许", { choose(true) }, Modifier.weight(1f), primary = true)
            }) { Text(ReportingExplanation) }
        }
    }
}

@Composable
internal fun ReportingRestartDialog(visibility: MutableTransitionState<Boolean>, allow: Boolean,
    controller: ReportingController, backdrop: Backdrop, restart: () -> Boolean, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    fun close() { if (!saving) dismiss() }
    fun save(immediate: Boolean) {
        if (saving || !visibility.targetState) return
        saving = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { controller.saveForNextStart(allow) }
            if (!saved) showKudoToast(context, "无法保存，请重试")
            else {
                dismiss()
                if (immediate && !restart()) showKudoToast(context, "已保存，请关闭应用后重新打开以生效")
                else if (!immediate) showKudoToast(context, "已保存，下次启动生效")
            }
            saving = false
        }
    }
    LiquidModal(visibility, if (allow) "启用 Sentry 错误上报？" else "关闭 Sentry 错误上报？", false,
        backdrop, LocalDisplaySettings.current.glass, ::close, showActions = false) {
        if (allow) Text(ReportingExplanation)
        Text("立即重启会中断正在运行的小程序和音乐，账号及本地数据会保留。选择下次启动时，本次运行的上报状态不变。")
        LiquidModalAction("立即重启生效", { save(true) }, Modifier.fillMaxWidth(), primary = true)
        LiquidModalAction("下次启动生效", { save(false) }, Modifier.fillMaxWidth())
        LiquidModalAction("取消", ::close, Modifier.fillMaxWidth())
    }
}
