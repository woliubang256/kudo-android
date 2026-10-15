package com.kudo.android

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.*
import com.kudo.android.ui.editor.EditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class EditorLifecycleUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: AppContainer
    private val editors = mutableListOf<EditorViewModel>()
    private val userId = Int.MIN_VALUE
    @Before fun create() { instrumentation.runOnMainSync { app = AppContainer(instrumentation.targetContext) } }
    @After fun clean() {
        instrumentation.runOnMainSync {
            editors.forEach { it.viewModelScope.cancel() }
            listOf(8, 9).forEach { app.settings.clearDraft("${userId}_700_$it") }
            listOf(0, 4, 98).forEach { app.settings.clearDraft("${userId}_new_$it") }
        }
    }
    private fun editor(floor: Int = 8, rules: suspend () -> ReplyRules = { ReplyRules(16, 64000) }, publish: suspend (Draft) -> PublishResult = { PublishResult(700, false) }): EditorViewModel {
        lateinit var value: EditorViewModel
        instrumentation.runOnMainSync { value = EditorViewModel(app, userId, 700, floor, publish, rulesRequest = rules); editors += value }
        runBlocking { withTimeout(5000) { while (value.rulesLoading) delay(10) } }
        return value
    }

    private fun newEditor(category: Int?, publish: suspend (Draft) -> PublishResult = { error("打开编辑器不能发送") }): EditorViewModel {
        lateinit var vm: EditorViewModel
        instrumentation.runOnMainSync {
            vm = EditorViewModel(app, userId, null, null, publish, categoryId = category)
            editors += vm
        }
        return vm
    }

    @Test fun categoryEditorsPreselectTheirCategoryAndKeepGeneralOtherCategoryAndManualDraftChoices() {
        instrumentation.runOnMainSync { app.settings.saveDraft("${userId}_new_0", Draft("首页草稿", "首页正文", 12)) }
        val four = newEditor(4)
        val other = newEditor(98)
        assertEquals(4, four.draft.categoryId)
        assertEquals(98, other.draft.categoryId)
        assertEquals("", four.draft.raw)
        instrumentation.runOnMainSync {
            four.edit(four.draft.copy(title = "分类四", raw = "分类草稿", categoryId = 98, tags = listOf("公告", "开发")))
            other.edit(other.draft.copy(raw = "另一分类草稿"))
        }
        val restored = newEditor(4)
        assertEquals("分类草稿", restored.draft.raw)
        assertEquals(listOf("公告", "开发"), restored.draft.tags)
        assertEquals(restored.draft.tags, AppSettings(instrumentation.targetContext).draft("${userId}_new_4").tags)
        assertEquals("手动改选的分类要随草稿恢复", 98, restored.draft.categoryId)
        assertEquals("首页正文", newEditor(null).draft.raw)
        assertEquals("另一分类草稿", newEditor(98).draft.raw)
        val saved = app.settings.drafts(userId).first { it.key == "${userId}_new_4" }
        assertEquals(4, saved.categoryContext)
        assertEquals(restored.draft.tags, saved.draft.tags)
        assertNull(saved.topicId)
        assertNull(saved.replyTo)
    }

    @Test fun categoryPublishUsesPreselectedCategoryAndOnlyClearsItsConfirmedDraft() = runBlocking {
        var published: Draft? = null
        val vm = newEditor(4) { published = it; PublishResult(701, false) }
        instrumentation.runOnMainSync {
            app.settings.saveDraft("${userId}_new_0", Draft(raw = "首页草稿"))
            app.settings.saveDraft("${userId}_new_98", Draft(raw = "其他分类草稿"))
            vm.edit(vm.draft.copy(title = "标题", raw = "确认发送的分类正文", tags = listOf("公告", "开发")))
        }
        assertNull(published)
        instrumentation.runOnMainSync { vm.publish() }
        withTimeout(5000) { vm.published.first() }
        assertEquals(4, published!!.categoryId)
        assertEquals(listOf("公告", "开发"), published!!.tags)
        assertEquals("", app.settings.draft("${userId}_new_4").raw)
        assertTrue(app.settings.draft("${userId}_new_4").tags.isEmpty())
        assertEquals("首页草稿", app.settings.draft("${userId}_new_0").raw)
        assertEquals("其他分类草稿", app.settings.draft("${userId}_new_98").raw)
    }

    @Test fun successfulReplyClearsBothStoredAndLiveDraftBeforeTheSameSheetReopens() = runBlocking {
        val vm = editor()
        instrumentation.runOnMainSync { vm.edit(Draft(raw = "这是达到网站最低字数要求的回复内容")); vm.publish() }
        withTimeout(5000) { vm.published.first() }
        instrumentation.runOnMainSync {
            assertEquals("", app.settings.draft("${userId}_700_8").raw)
            assertEquals("", vm.draft.raw)
            assertFalse(vm.sending)
        }
    }

    @Test fun tooShortReplyNeverPublishesAndKeepsItsDraft() = runBlocking {
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val vm = editor { writes.incrementAndGet(); PublishResult(700, false) }
        instrumentation.runOnMainSync { vm.edit(Draft(raw = "短回复")); vm.publish() }
        withTimeout(5000) { while (vm.sending) delay(20) }
        assertEquals("不足网站最小字数时不能发送", 0, writes.get())
        assertEquals("短回复", app.settings.draft("${userId}_700_8").raw)
    }

    @Test fun closingAndReconstructingKeepsDraftsSeparatedByActualReplyFloor() {
        val eight = editor(8)
        val nine = editor(9)
        instrumentation.runOnMainSync { eight.edit(Draft(raw = "八楼草稿")); nine.edit(Draft(raw = "九楼草稿")) }
        val restoredEight = editor(8)
        val restoredNine = editor(9)
        assertEquals("八楼草稿", restoredEight.draft.raw)
        assertEquals("九楼草稿", restoredNine.draft.raw)
    }

    @Test fun failedPublishKeepsDraftAndRestoresEditing() = runBlocking {
        val vm = editor { throw IllegalStateException("夹具失败") }
        val raw = "这是发送失败之后必须保留下来的回复草稿"
        instrumentation.runOnMainSync { vm.edit(Draft(raw = raw)); vm.publish() }
        withTimeout(5000) { while (vm.sending) delay(20) }
        assertEquals(raw, vm.draft.raw)
        assertEquals(raw, app.settings.draft("${userId}_700_8").raw)
        assertEquals("夹具失败", vm.error)
    }

    @Test fun topicSendingLocksTagsAndFailurePreservesThemForReopening() = runBlocking {
        val pending = CompletableDeferred<PublishResult>()
        val vm = newEditor(4) { pending.await() }
        instrumentation.runOnMainSync {
            vm.edit(Draft("标签草稿", "正文", 4, tags = listOf("公告", "开发")))
            vm.publish()
            vm.edit(vm.draft.copy(tags = emptyList()))
        }
        assertEquals(listOf("公告", "开发"), vm.draft.tags)
        pending.completeExceptionally(IllegalStateException("标签不满足站点规则"))
        withTimeout(5000) { while (vm.sending) delay(10) }
        assertEquals("标签不满足站点规则", vm.error)
        assertEquals(listOf("公告", "开发"), newEditor(4).draft.tags)
    }

    @Test fun unavailableRulesPreventPublishingAndCanBeRetriedWithoutLosingText() = runBlocking {
        var ready = false
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val vm = editor(rules = { if (!ready) error("配置夹具失败") else ReplyRules(4, 64000) }) { writes.incrementAndGet(); PublishResult(700, false) }
        instrumentation.runOnMainSync { vm.edit(Draft(raw = "四字回复")); vm.publish() }
        assertEquals(0, writes.get())
        assertEquals("四字回复", vm.draft.raw)
        instrumentation.runOnMainSync { ready = true; vm.reloadReplyRules() }
        withTimeout(5000) { while (vm.rulesLoading) delay(10) }
        instrumentation.runOnMainSync { vm.publish() }
        withTimeout(5000) { vm.published.first() }
        assertEquals(1, writes.get())
    }
}
