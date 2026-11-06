package com.kudo.android

import com.kudo.android.ui.web.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MiniAppActionsTest {
    private val id = "01a1966a-f4a0-7b43-ad9c-c46b7f0e0281"
    private fun rating(score: Int? = 3) = JSONObject().put("rating_average", 4.25).put("rating_count", 8)
        .put("my_rating", score ?: JSONObject.NULL)
    private fun context(release: Long? = 328, allowed: Boolean = true) = JSONObject().put("app", rating().put("app_id", id)
        .put("can_rate", allowed).put("can_tip", allowed).put("can_report", allowed)).put("release_id", release ?: JSONObject.NULL)
    private fun tips(balance: Long = 10, next: String? = null) = JSONObject("""{"amounts":[1,3,5,10]}""")
        .put("balance", balance).put("next_tip_at", next ?: JSONObject.NULL)
    private fun flags() = JSONObject("""{"minimum":5,"reasons":[{"id":8,"key":"notify_moderators","name":"其他内容","require_message":true}]}""")

    @Test fun modelsRejectMalformedRatingsAndKeepUnavailableReleaseUnknown() {
        assertNull(MiniAppActionContext.parse(context(null), id).releaseId)
        assertEquals(3, MiniAppActionContext.parse(context(), id).rating.mine)
        assertThrows(IllegalArgumentException::class.java) { MiniAppActionContext.parse(context(), "another") }
        listOf(-1, 6, "4", 2.5).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { MiniAppRating.parse(rating().put("my_rating", invalid)) }
        }
        assertThrows(IllegalArgumentException::class.java) { MiniAppRating.parse(rating().put("rating_average", "4.25")) }
    }

    @Test fun tipAvailabilityUsesServerAmountsBalanceAndExactCooldownBoundary() {
        val now = Instant.parse("2026-11-06T01:00:00Z")
        val status = MiniAppTipStatus.parse(tips(3, "2026-11-06T01:00:01Z"))
        assertFalse(status.allows(1, now))
        assertTrue(status.allows(3, now.plusSeconds(1)))
        assertFalse(status.allows(5, now.plusSeconds(1)))
        assertFalse(status.allows(2, now.plusSeconds(1)))
        assertFalse(status.allows(null, now.plusSeconds(1)))
        assertThrows(IllegalArgumentException::class.java) { MiniAppTipStatus.parse(tips(-1)) }
    }

    @Test fun ratingCannotDoubleSubmitAndSuccessfulEditsUpdateTheStatistics() = runTest {
        val response = CompletableDeferred<JSONObject>()
        var writes = 0
        val saved = mutableListOf<MiniAppRating>()
        val vm = MiniAppActionsController(backgroundScope, id, true, { operation, _ ->
            if (operation == MiniAppOperation.CONTEXT) context() else { writes++; response.await() }
        }, saved::add)
        vm.open(MiniAppPanel.RATING); runCurrent()
        vm.rate(5); vm.rate(1); runCurrent()
        assertEquals(1, writes); assertTrue(vm.busy)
        response.complete(rating(5)); runCurrent()
        assertEquals(5, vm.context!!.rating.mine)
        assertEquals(1, saved.size); assertFalse(vm.busy)
        vm.dispose()
    }

    @Test fun lostTipResponseKeepsItsRequestIdAndDisallowsChangingTheAmount() = runTest {
        val writes = mutableListOf<JSONObject>()
        var statusCalls = 0
        val vm = MiniAppActionsController(backgroundScope, id, true, { operation, payload -> when (operation) {
            MiniAppOperation.CONTEXT -> context()
            MiniAppOperation.TIP_STATUS -> { statusCalls++; tips() }
            MiniAppOperation.TIP -> {
                writes += payload
                if (writes.size == 1) throw MiniAppActionFailure("网络中断", true)
                tips(7, "2099-01-01T00:00:00Z").put("amount", 3)
            }
            else -> error("Unexpected operation")
        } })
        vm.open(MiniAppPanel.TIP); runCurrent(); vm.chooseAmount(3); vm.tip(); runCurrent()
        assertTrue(vm.tipUncertain); assertEquals(2, statusCalls)
        vm.chooseAmount(5); assertEquals(3L, vm.amount)
        vm.close(); vm.open(MiniAppPanel.TIP); runCurrent(); vm.tip(); runCurrent()
        assertEquals(2, writes.size)
        assertEquals(writes[0].getString("request_id"), writes[1].getString("request_id"))
        assertEquals(7L, vm.tips!!.balance); assertFalse(vm.canTip); assertFalse(vm.tipUncertain)
        assertEquals("已打赏 3 积分", vm.notice)
        vm.dispose()
    }

    @Test fun reportsRequireTheRunningReleaseAndValidateNecessaryExplanation() = runTest {
        var submitted: JSONObject? = null
        val vm = MiniAppActionsController(backgroundScope, id, true, { operation, payload -> when (operation) {
            MiniAppOperation.CONTEXT -> context(328)
            MiniAppOperation.FLAGS -> flags()
            MiniAppOperation.REPORT -> { submitted = payload; JSONObject().put("success", true) }
            else -> error("Unexpected operation")
        } })
        vm.open(MiniAppPanel.REPORT); runCurrent()
        vm.chooseReason(vm.flags!!.reasons.single()); vm.message("短"); vm.report(); runCurrent(); assertNull(submitted)
        vm.message("  完整举报说明  "); vm.report(); vm.report(); runCurrent()
        assertEquals(328L, submitted!!.getLong("release_id"))
        assertEquals("完整举报说明", submitted!!.getString("message"))
        assertFalse(vm.canReport)
        vm.close(); vm.open(MiniAppPanel.REPORT); runCurrent()
        assertEquals("举报已提交", vm.notice)
        vm.dispose()
    }

    @Test fun alreadyChargedTipCanReconcileTheSameRequestDuringCooldownWithLowerBalance() = runTest {
        var charged = false
        val ids = mutableListOf<String>()
        val vm = MiniAppActionsController(backgroundScope, id, true, { operation, payload -> when (operation) {
            MiniAppOperation.CONTEXT -> context()
            MiniAppOperation.TIP_STATUS -> if (charged) tips(1, "2099-01-01T00:00:00Z") else tips(4)
            MiniAppOperation.TIP -> {
                ids += payload.getString("request_id")
                if (!charged) { charged = true; throw MiniAppActionFailure("响应丢失", true) }
                tips(1, "2099-01-01T00:00:00Z").put("amount", 3)
            }
            else -> error("Unexpected operation")
        } })
        vm.open(MiniAppPanel.TIP); runCurrent(); vm.chooseAmount(3); vm.tip(); runCurrent()
        assertTrue("余额下降和冷却不能阻止核对已经发出的同一笔请求", vm.canTip)
        vm.tip(); runCurrent()
        assertEquals(2, ids.size); assertEquals(ids[0], ids[1]); assertEquals("已打赏 3 积分", vm.notice)
        vm.dispose()
    }

    @Test fun uncertainReportCannotBeSubmittedAgainEvenAfterReopening() = runTest {
        var writes = 0
        val vm = MiniAppActionsController(backgroundScope, id, true, { operation, _ -> when (operation) {
            MiniAppOperation.CONTEXT -> context()
            MiniAppOperation.FLAGS -> flags()
            MiniAppOperation.REPORT -> { writes++; throw MiniAppActionFailure("连接中断", true) }
            else -> error("Unexpected operation")
        } })
        vm.open(MiniAppPanel.REPORT); runCurrent(); vm.chooseReason(vm.flags!!.reasons.single()); vm.message("完整举报说明")
        vm.report(); runCurrent(); assertTrue(vm.reportLocked)
        vm.close(); vm.open(MiniAppPanel.REPORT); vm.report(); runCurrent()
        assertEquals(1, writes); assertFalse(vm.canReport)
        vm.dispose()
    }

    @Test fun guestAndServerDenialNeverSubmitAndMissingReleaseNeverFallsBackToLatest() = runTest {
        var calls = 0
        val guest = MiniAppActionsController(backgroundScope, id, false, { _, _ -> calls++; context() })
        guest.open(MiniAppPanel.RATING); guest.rate(5); runCurrent(); assertEquals(0, calls)
        val denied = MiniAppActionsController(backgroundScope, id, true, { _, _ -> calls++; context(allowed = false) })
        denied.open(MiniAppPanel.RATING); runCurrent(); denied.rate(5); assertEquals(1, calls)
        val noRelease = MiniAppActionsController(backgroundScope, id, true, { _, _ -> calls++; context(null) })
        noRelease.open(MiniAppPanel.REPORT); runCurrent()
        assertNull(noRelease.flags); assertFalse(noRelease.canReport); assertEquals(2, calls)
        listOf(guest, denied, noRelease).forEach { it.dispose() }
    }

    @Test fun disposedAccountCannotApplyALateRatingOrInvokeItsSavedCallback() = runTest {
        val pending = CompletableDeferred<JSONObject>()
        var callbacks = 0
        val vm = MiniAppActionsController(backgroundScope, id, true, { operation, _ ->
            if (operation == MiniAppOperation.CONTEXT) context() else withContext(NonCancellable) { pending.await() }
        }, { callbacks++ })
        vm.open(MiniAppPanel.RATING); runCurrent(); vm.rate(5); runCurrent(); vm.dispose()
        pending.complete(rating(5)); runCurrent()
        assertEquals(0, callbacks); assertEquals(3, vm.context!!.rating.mine)
    }

    @Test fun closingAReadCannotFillOrReopenANewPanelWithOldData() = runTest {
        val pending = CompletableDeferred<JSONObject>()
        var contexts = 0
        val vm = MiniAppActionsController(backgroundScope, id, true, { operation, _ -> when (operation) {
            MiniAppOperation.CONTEXT -> if (++contexts == 1) withContext(NonCancellable) { pending.await() } else context()
            MiniAppOperation.TIP_STATUS -> tips()
            else -> error("Unexpected operation")
        } })
        vm.open(MiniAppPanel.RATING); runCurrent(); vm.open(MiniAppPanel.TIP); runCurrent()
        pending.complete(context(allowed = false)); runCurrent()
        assertNotNull(vm.tips); assertTrue(vm.context!!.canTip); assertNull(vm.error)
        vm.dispose()
    }
}
