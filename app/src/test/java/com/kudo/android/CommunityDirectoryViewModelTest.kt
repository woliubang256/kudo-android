package com.kudo.android

import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.account.CommunityDirectoryViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityDirectoryViewModelTest {
    private fun directory(id: Int?) = CommunityDirectory(id, "fixture", emptyList(), emptyList(), emptyList(), true)

    @Test fun cachedEntriesAndInFlightRequestsDoNotReloadUntilStaleOrExplicitlyRefreshed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        var now = 100_000L
        val first = CompletableDeferred<CommunityDirectory>()
        val model = CommunityDirectoryViewModel({ calls++; if (calls == 1) first.await() else directory(it) }, { emptyList() }, { now })
        try {
            model.useAccount(7); model.load(); model.load(); runCurrent()
            assertEquals(1, calls)
            first.complete(directory(7)); advanceUntilIdle()
            model.load(); model.loadIfStale(); advanceUntilIdle()
            assertEquals(1, calls)
            now += 60_001
            model.loadIfStale(); advanceUntilIdle()
            assertEquals(2, calls)
            model.load(refresh = true); advanceUntilIdle()
            assertEquals(3, calls)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun accountSwitchClearsDirectoryTagsAndRejectsLateOldAccountResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val old = CompletableDeferred<CommunityDirectory>()
        val model = CommunityDirectoryViewModel({ id ->
            if (id == 7) withContext(NonCancellable) { old.await() } else directory(id)
        }, { listOf(DirectoryTagGroup("新账号", emptyList())) })
        try {
            model.useAccount(7); model.load(); runCurrent()
            model.useAccount(8)
            assertNull(model.state.value.directory); assertNull(model.state.value.tagGroups)
            model.load(); model.loadTags(); runCurrent()
            old.complete(directory(7)); advanceUntilIdle()
            assertEquals(8, model.state.value.directory?.accountId)
            assertEquals("新账号", model.state.value.tagGroups?.single()?.name)
            model.useAccount(null)
            assertNull(model.state.value.directory); assertNull(model.state.value.tagGroups)
        } finally { old.complete(directory(7)); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun refreshFailureKeepsSuccessfulDataAndIndependentTagFailureCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = false
        var tagCalls = 0
        val model = CommunityDirectoryViewModel({ if (fail) throw ApiFailure(FailureKind.NETWORK, "离线") else directory(it) }, {
            if (++tagCalls == 1) throw ApiFailure(FailureKind.SERVER, "标签失败")
            listOf(DirectoryTagGroup("分组", listOf(DirectoryTag(TopicTag("标签")))))
        })
        try {
            model.useAccount(7); model.load(); advanceUntilIdle()
            fail = true; model.load(refresh = true); model.loadTags(); advanceUntilIdle()
            assertNotNull(model.state.value.directory); assertNotNull(model.state.value.error)
            assertNotNull(model.state.value.tagsError); assertNull(model.state.value.tagGroups)
            model.loadTags(); advanceUntilIdle()
            assertNull(model.state.value.tagsError); assertEquals("标签", model.state.value.tagGroups?.single()?.tags?.single()?.tag?.name)
            assertNotNull(model.state.value.error)
        } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
