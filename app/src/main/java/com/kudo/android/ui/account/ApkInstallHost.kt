package com.kudo.android.ui.account

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kudo.android.media.*
import com.kudo.android.ui.components.showKudoToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal val LocalApkInstall = staticCompositionLocalOf<((ApkInstallRequest) -> Unit)?> { null }
internal val LocalApkPreparing = staticCompositionLocalOf { false }

internal sealed interface ApkInstallState {
    data object Idle : ApkInstallState
    data object Preparing : ApkInstallState
    data class Ready(val apk: PreparedApk) : ApkInstallState
    data class Permission(val apk: PreparedApk) : ApkInstallState
    data class Failed(val message: String) : ApkInstallState
}

internal class ApkInstallViewModel(private val prepare: suspend (ApkInstallRequest) -> PreparedApk) : ViewModel() {
    private val current = MutableStateFlow<ApkInstallState>(ApkInstallState.Idle)
    val state = current.asStateFlow()
    fun install(request: ApkInstallRequest) {
        if (current.value != ApkInstallState.Idle) return
        current.value = ApkInstallState.Preparing
        viewModelScope.launch {
            try { current.value = ApkInstallState.Ready(prepare(request)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { current.value = ApkInstallState.Failed(e.message ?: "无法读取安装包，请重新下载") }
        }
    }
    fun awaitPermission(apk: PreparedApk) { current.value = ApkInstallState.Permission(apk) }
    fun permissionResult(granted: Boolean) {
        val value = current.value as? ApkInstallState.Permission ?: return
        current.value = if (granted) ApkInstallState.Ready(value.apk) else ApkInstallState.Failed("未允许安装应用，可稍后点击安装重试")
    }
    fun consume() { current.value = ApkInstallState.Idle }
}

@Composable
internal fun ApkInstallHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val model: ApkInstallViewModel = viewModel(key = "apk-installer", factory = viewModelFactory {
        initializer { ApkInstallViewModel(ApkInstaller(context.applicationContext)::prepare) }
    })
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.permissionResult(context.packageManager.canRequestPackageInstalls())
    }
    ObserveApkInstallation(model, { context.packageManager.canRequestPackageInstalls() },
        { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())) },
        { context.startActivity(apkInstallIntent(it)) }, { showKudoToast(context, it) }, content)
}

@Composable
internal fun ObserveApkInstallation(model: ApkInstallViewModel, canInstall: () -> Boolean, openPermission: () -> Unit,
    launchInstall: (PreparedApk) -> Unit, message: (String) -> Unit, content: @Composable () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(model) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    LaunchedEffect(state, resumed) {
        if (!resumed) return@LaunchedEffect
        when (val value = state) {
            is ApkInstallState.Ready -> {
                if (canInstall()) {
                    model.consume()
                    try { launchInstall(value.apk) }
                    catch (_: Exception) { message("无法打开系统安装器，请稍后重试") }
                } else {
                    model.awaitPermission(value.apk)
                    try { openPermission() }
                    catch (_: Exception) { model.permissionResult(false) }
                }
            }
            is ApkInstallState.Failed -> { message(value.message); model.consume() }
            else -> Unit
        }
    }
    CompositionLocalProvider(LocalApkInstall provides model::install, LocalApkPreparing provides (state == ApkInstallState.Preparing)) { content() }
}
