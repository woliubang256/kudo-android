package com.kudo.android.ui.web

import androidx.compose.runtime.*
import com.kudo.android.data.FlagConfiguration
import com.kudo.android.data.FlagReason
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** One app/account only. Reads can be discarded; writes never replay automatically. */
internal class MiniAppActionsController(
    private val scope: CoroutineScope, private val id: String, val signedIn: Boolean,
    private val request: suspend (MiniAppOperation, JSONObject) -> JSONObject,
    private val ratingSaved: (MiniAppRating) -> Unit = {},
) {
    var context by mutableStateOf<MiniAppActionContext?>(null); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var tips by mutableStateOf<MiniAppTipStatus?>(null); private set
    var amount by mutableStateOf<Long?>(null); private set
    var flags by mutableStateOf<FlagConfiguration?>(null); private set
    var reason by mutableStateOf<FlagReason?>(null); private set
    var message by mutableStateOf(""); private set
    var confirmed by mutableStateOf(false); private set
    var reportLocked by mutableStateOf(false); private set
    var tipUncertain by mutableStateOf(false); private set
    private var reportSent = false
    private var tipRequest: Pair<Long, String>? = null
    private var panel: MiniAppPanel? = null
    private var job: Job? = null
    private var generation = 0
    private var disposed = false
    val canRate get() = !loading && !busy && context?.canRate == true
    val reconcilingTip get() = tipUncertain && amount != null && tipRequest?.first == amount
    val canTip get() = !loading && !busy && context?.canTip == true && tips != null && (reconcilingTip || tips?.allows(amount) == true)
    val canReport get() = !loading && !busy && !reportLocked && !reportSent && context?.canReport == true &&
        context?.releaseId != null && flags?.valid(reason, message, confirmed) == true

    fun open(panel: MiniAppPanel) {
        if (disposed || busy) return
        close()
        this.panel = panel
        error = null; notice = null; context = null; tips = null; flags = null
        reason = null; message = ""; confirmed = false
        if (!tipUncertain) amount = null
        if (!signedIn) { error = "请先登录后再操作"; return }
        if (panel == MiniAppPanel.REPORT && (reportLocked || reportSent)) {
            notice = if (reportSent) "举报已提交" else null
            if (reportLocked) error = "上次举报结果尚未确认，请到网站核对后再操作"
            return
        }
        val token = generation
        loading = true
        job = scope.launch {
            try {
                val current = MiniAppActionContext.parse(call(MiniAppOperation.CONTEXT), id)
                if (!valid(token)) return@launch
                context = current
                when (panel) {
                    MiniAppPanel.RATING -> check(current.canRate) { "当前账号暂时不能为此应用评分" }
                    MiniAppPanel.TIP -> {
                        check(current.canTip) { "此应用暂不支持打赏，或当前账号无法打赏" }
                        val status = MiniAppTipStatus.parse(call(MiniAppOperation.TIP_STATUS))
                        if (valid(token)) tips = status
                    }
                    MiniAppPanel.REPORT -> {
                        check(current.canReport) { "当前账号暂时不能举报此应用" }
                        check(current.releaseId != null) { "暂时无法确认运行版本，请重新进入小程序后重试" }
                        val config = parseMiniAppFlags(call(MiniAppOperation.FLAGS))
                        if (valid(token)) flags = config
                    }
                    else -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (valid(token)) error = failure.message ?: "加载失败，请重试" }
            finally { if (valid(token)) loading = false }
        }
    }

    fun retry() { panel?.let(::open) }
    fun chooseAmount(value: Long) {
        if (!busy && !loading && tips?.allows(value) == true && (!tipUncertain || amount == value)) amount = value
    }
    fun chooseReason(value: FlagReason) {
        if (!busy && !reportLocked && value in flags?.reasons.orEmpty()) { reason = value; message = ""; confirmed = false }
    }
    fun message(value: String) { if (!busy && !reportLocked) message = value.take(2000) }
    fun confirm(value: Boolean) { if (!busy && !reportLocked) confirmed = value }

    fun rate(score: Int) {
        if (!canRate || score !in 1..5) return
        write {
            val result = MiniAppRating.parse(call(MiniAppOperation.RATING, JSONObject().put("score", score)))
            check(result.mine == score) { "评分结果尚未确认，请重试" }
            context = context?.copy(rating = result)
            notice = "评分已更新"
            ratingSaved(result)
        }
    }

    fun tip() {
        if (!canTip) return
        val selected = amount ?: return
        val token = tipRequest?.takeIf { it.first == selected } ?: (selected to UUID.randomUUID().toString()).also { tipRequest = it }
        write {
            try {
                val result = call(MiniAppOperation.TIP, JSONObject().put("amount", selected).put("request_id", token.second))
                val status = MiniAppTipStatus.parse(result)
                check((result.opt("amount") as? Number)?.toLong() == selected) { "打赏结果尚未确认，请到网站核对" }
                tips = status; notice = "已打赏 $selected 积分"; amount = null; tipRequest = null; tipUncertain = false
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                tipUncertain = (failure as? MiniAppActionFailure)?.uncertain != false
                try { tips = MiniAppTipStatus.parse(call(MiniAppOperation.TIP_STATUS)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the original request id and result uncertainty. */ }
                throw failure
            }
        }
    }

    fun report() {
        if (!canReport) return
        val selected = reason ?: return
        val release = context?.releaseId ?: return
        write {
            try {
                call(MiniAppOperation.REPORT, JSONObject().put("flag_type_id", selected.id).put("release_id", release)
                    .put("message", if (selected.requireMessage) message.trim() else ""))
                reportSent = true; notice = "举报已提交"
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                reportLocked = (failure as? MiniAppActionFailure)?.uncertain != false
                throw failure
            }
        }
    }

    private fun write(block: suspend () -> Unit) {
        if (busy || disposed) return
        val token = generation
        busy = true; error = null; notice = null
        job = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (valid(token)) error = failure.message ?: "操作失败，请稍后重试" }
            finally { if (valid(token)) busy = false }
        }
    }
    private fun valid(token: Int) = !disposed && token == generation
    private suspend fun call(operation: MiniAppOperation, payload: JSONObject = JSONObject()): JSONObject {
        val token = generation
        val result = request(operation, payload)
        currentCoroutineContext().ensureActive()
        if (!valid(token)) throw CancellationException("小程序已退出")
        return result
    }
    fun close() {
        if (busy) return
        generation++; job?.cancel(); job = null; loading = false; panel = null
    }
    fun dispose() { disposed = true; generation++; job?.cancel() }
}
