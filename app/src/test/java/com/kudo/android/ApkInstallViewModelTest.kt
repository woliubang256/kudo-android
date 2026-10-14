package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.media.*
import com.kudo.android.ui.account.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ApkInstallViewModelTest {
    private val request = ApkInstallRequest(DownloadTask("id", "https://example.org/a.apk", "a.apk", DownloadStatus.COMPLETED))
    private val prepared = PreparedApk("content://fixture/a.apk")

    @Test fun preparingDeduplicatesAndPermissionGrantContinuesSameSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        val response = CompletableDeferred<PreparedApk>()
        val model = ApkInstallViewModel { calls++; response.await() }
        try {
            model.install(request); model.install(request); runCurrent()
            assertEquals(1, calls); assertEquals(ApkInstallState.Preparing, model.state.value)
            response.complete(prepared); advanceUntilIdle()
            assertEquals(ApkInstallState.Ready(prepared), model.state.value)
            model.awaitPermission(prepared); model.permissionResult(true)
            assertEquals(ApkInstallState.Ready(prepared), model.state.value)
            model.consume(); assertEquals(ApkInstallState.Idle, model.state.value)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun permissionDenialAndInvalidApkCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = true
        val model = ApkInstallViewModel { if (fail) throw IOException("安装包无效") else prepared }
        try {
            model.install(request); advanceUntilIdle()
            assertEquals(ApkInstallState.Failed("安装包无效"), model.state.value)
            model.consume(); fail = false; model.install(request); advanceUntilIdle()
            model.awaitPermission(prepared); model.permissionResult(false)
            assertTrue(model.state.value is ApkInstallState.Failed)
            model.consume(); model.install(request); advanceUntilIdle()
            assertEquals(ApkInstallState.Ready(prepared), model.state.value)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun compatibleSignersAcceptIdentityOrForwardRotationAndRejectUnrelatedSignatures() {
        assertTrue(compatibleSigners(setOf("old"), setOf("old"), emptySet()))
        assertTrue(compatibleSigners(setOf("old"), setOf("new"), setOf("old", "new")))
        assertTrue(compatibleSigners(setOf("a", "b"), setOf("b", "a"), emptySet()))
        assertFalse(compatibleSigners(setOf("old"), setOf("unrelated"), setOf("unrelated")))
        assertFalse(compatibleSigners(setOf("new"), setOf("old"), setOf("old")))
        assertFalse(compatibleSigners(setOf("a", "b"), setOf("a"), setOf("a", "b")))
        assertFalse(compatibleSigners(emptySet(), emptySet(), emptySet()))
    }
}
