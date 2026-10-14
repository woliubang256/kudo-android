package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.GitHubRelease
import com.kudo.android.data.UpdateFailure
import com.kudo.android.ui.account.HomeUpdateViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeUpdateViewModelTest {
    private val release = GitHubRelease("0.10.0", "新版", "更新日志", "2026-10-14", "https://github.com/")

    @Test fun inFlightAndDismissedChecksNeverRestartButNewSessionCanCheck() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = mutableListOf<HomeUpdateViewModel>()
        try {
            var requests = 0
            val response = CompletableDeferred<GitHubRelease>()
            val model = HomeUpdateViewModel({ requests++; response.await() }, "0.9.999").also(models::add)
            model.checkOnce(); model.checkOnce(); runCurrent()
            assertEquals(1, requests); assertNull(model.release.value)
            response.complete(release); advanceUntilIdle()
            assertEquals(release, model.release.value)
            model.dismiss(); model.checkOnce(); advanceUntilIdle()
            assertNull(model.release.value); assertEquals(1, requests)
            val next = HomeUpdateViewModel({ requests++; release }, "0.9.999").also(models::add)
            next.checkOnce(); advanceUntilIdle()
            assertEquals(release, next.release.value); assertEquals(2, requests)
        } finally { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    }

    @Test fun currentOlderMalformedAndFailedResultsAreSilentAndNotRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = mutableListOf<HomeUpdateViewModel>()
        try {
            for (version in listOf("0.10.0", "0.9.999", "invalid", null)) {
                var requests = 0
                val model = HomeUpdateViewModel({
                    requests++
                    if (version == null) throw UpdateFailure("无法连接 GitHub")
                    release.copy(version = version)
                }, "0.10.0").also(models::add)
                model.checkOnce(); advanceUntilIdle(); model.checkOnce(); advanceUntilIdle()
                assertNull(model.release.value); assertEquals(1, requests)
            }
        } finally { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    }

    @Test fun endingSessionCancelsInFlightRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var cancelled = false
        val model = HomeUpdateViewModel({
            try { awaitCancellation() } finally { cancelled = true }
        }, "0.9.999")
        try {
            model.checkOnce(); runCurrent()
            model.viewModelScope.cancel(); runCurrent()
            assertTrue(cancelled); assertNull(model.release.value)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
