package com.kudo.android

import androidx.lifecycle.viewModelScope
import androidx.compose.ui.text.TextRange
import com.kudo.android.data.*
import com.kudo.android.ui.editor.PostEditViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PostEditViewModelTest {
    private val post = Post(10, 1, Member(2, "me", "", ""), "<p>正文</p>", "", 0, false, null, null, false, canEdit = true)
    private val source = EditablePost(post, 5, "原文", "标题")
    private suspend fun await(predicate: () -> Boolean) = withContext(Dispatchers.IO) { withTimeout(5000) { while (!predicate()) delay(10) } }
    @Test fun imageUploadKeepsTypingAndOriginalInsertionPosition() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<String>()
        val vm = PostEditViewModel({ source.copy(raw = "前后") }, { _, _ -> post })
        try {
            await { !vm.loading }
            vm.prepareImageUpload(TextRange(1)); vm.upload { gate.await() }
            vm.edit(vm.draft!!.copy(raw = "前继续写后"))
            vm.edit(vm.draft!!.copy(raw = "开头前继续写后"))
            vm.edit(vm.draft!!.copy(raw = "开头前继续写后结尾"))
            gate.complete("![图](upload://a)"); await { !vm.uploading }
            assertEquals("开头前继续写\n\n![图](upload://a)\n\n后结尾", vm.draft!!.raw)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun restoredDraftKeepsItsOriginalSnapshotAndEncoding() {
        val draft = PostEditDraft("原文", "标题", "修改 ![图](upload://a)", "新标题", "修正")
        assertEquals(draft, PostEditDraft.fromJson(draft.toJson()))
    }
    @Test fun doubleSaveSendsOnceAndClearsDraftOnlyOnSuccess() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val writes = AtomicInteger(); val gate = CompletableDeferred<Unit>(); var stored: PostEditDraft? = null
        val vm = PostEditViewModel({ source }, { original, changed ->
            assertEquals("原文", original.raw); assertEquals("修改", changed.raw)
            writes.incrementAndGet(); gate.await(); post.copy(version = 2)
        }, persist = { stored = it })
        try {
            await { !vm.loading }; vm.edit(vm.draft!!.copy(raw = "修改")); assertNotNull(stored)
            vm.save(); vm.save(); await { writes.get() == 1 }; assertNotNull(stored)
            gate.complete(Unit); val result = vm.saved.first()
            assertEquals(2, result.post.version); assertNull(stored); assertNull(vm.draft)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun failureRetainsChangesAndUnknownResultRequiresReadBeforeRetry() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var latest = source; val writes = AtomicInteger()
        val vm = PostEditViewModel({ latest }, { _, _ -> writes.incrementAndGet(); throw ApiFailure(FailureKind.NETWORK, "网络中断") })
        try {
            await { !vm.loading }; vm.edit(vm.draft!!.copy(raw = "修改")); vm.save(); await { !vm.sending }
            assertEquals("修改", vm.draft!!.raw); assertTrue(vm.writeLocked)
            vm.save(); assertEquals(1, writes.get())
            latest = source.copy(raw = "修改", post = post.copy(version = 2))
            vm.load(); assertEquals(2, vm.saved.first().post.version); assertEquals(1, writes.get())
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun restoredConflictPreservesInputUntilExplicitReload() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val pending = PostEditDraft("旧原文", "标题", "我的修改", "标题")
        val writes = AtomicInteger(); var stored: PostEditDraft? = pending
        val vm = PostEditViewModel({ source }, { _, _ -> writes.incrementAndGet(); post }, restore = { pending }, persist = { stored = it })
        try {
            await { !vm.loading }; assertTrue(vm.conflict); assertEquals("我的修改", vm.draft!!.raw)
            vm.save(); assertEquals(0, writes.get()); assertEquals(pending, stored)
            vm.useLatest(); assertEquals("原文", vm.draft!!.raw); assertFalse(vm.conflict); assertNull(stored)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun permissionReadFailureCannotSaveAnOldDraft() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val writes = AtomicInteger()
        val vm = PostEditViewModel({ throw ApiFailure(FailureKind.FORBIDDEN, "已过期") }, { _, _ -> writes.incrementAndGet(); post },
            restore = { PostEditDraft("原文", "标题", "修改", "标题") })
        try {
            await { !vm.loading }; assertNotNull(vm.error); assertFalse(vm.canSave); vm.save(); assertEquals(0, writes.get())
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }
    @Test fun permissionLossOnReloadBlocksPreviouslyLoadedDraft() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var expired = false
        val vm = PostEditViewModel({ if (expired) throw ApiFailure(FailureKind.FORBIDDEN, "已过期") else source }, { _, _ -> post })
        try {
            await { !vm.loading }; vm.edit(vm.draft!!.copy(raw = "修改")); assertTrue(vm.canSave)
            expired = true; vm.load(); await { !vm.loading }; assertFalse(vm.canSave)
            assertEquals("修改", vm.draft!!.raw)
        } finally { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin(); Dispatchers.resetMain() }
    }
}
