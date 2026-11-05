package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.apps.AppSquareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppSquareAboutTest {
    private val first = MiniAppEntry("01a1966a-f4a0-7b43-ad9c-c46b7f0e0281", "2048", null)
    private val second = first.copy(id = "01a1966e-935a-79e0-8c76-9b0f7552c578", name = "打砖块")
    private fun details(id: String) = MiniAppDetails(id, id, "", null, "", null, 4.35, 7L)
    private suspend fun AppSquareViewModel.aboutFinished() = withContext(Dispatchers.IO) {
        withTimeout(5000) { state.first { it.about?.loading == false } }.about!!
    }

    @Test fun aboutRetriesWithoutReloadingDirectoryAndRejectsMismatchedIdentity() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var fail = true
        var wrongId = false
        var directoryCalls = 0
        val requested = mutableListOf<String>()
        val vm = AppSquareViewModel(loadInfo = {
            requested.add(it)
            if (fail) error("离线")
            details(if (wrongId) second.id else it)
        }) { _, _, _ -> directoryCalls++; MiniAppDirectoryPage(listOf(first), false) }
        try {
            vm.showAbout(first)
            assertNotNull(vm.aboutFinished().error)
            fail = false; vm.retryAbout()
            assertEquals(first.id, vm.aboutFinished().info?.id)
            wrongId = true; vm.retryAbout()
            assertTrue(vm.aboutFinished().error is IllegalArgumentException)
            assertEquals(listOf(first.id, first.id, first.id), requested)
            assertEquals(1, directoryCalls)
            vm.dismissAbout(); assertNull(vm.state.value.about)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun slowOldInfoCannotReplaceANewAppOrReopenAfterDismissal() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val vm = AppSquareViewModel(loadInfo = {
            if (it == first.id) withContext(NonCancellable) { started.complete(Unit); release.await() }
            details(it)
        }) { _, _, _ -> MiniAppDirectoryPage(listOf(first, second), false) }
        try {
            vm.showAbout(first)
            withContext(Dispatchers.IO) { withTimeout(5000) { started.await() } }
            vm.showAbout(second)
            assertEquals(second.id, vm.aboutFinished().info?.id)
            vm.dismissAbout(); release.complete(Unit)
            vm.viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertNull(vm.state.value.about)
            vm.showAbout(second); vm.aboutFinished()
            vm.sort(MiniAppSort.HOT)
            assertNull(vm.state.value.about)
        } finally { release.complete(Unit); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }
}
