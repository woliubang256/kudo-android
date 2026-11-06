package com.kudo.android.ui.web

import com.kudo.android.data.FlagConfiguration
import com.kudo.android.data.FlagReason
import com.kudo.android.data.objects
import com.kudo.android.data.plainText
import org.json.JSONObject
import java.time.Instant

internal enum class MiniAppOperation(val wire: String) {
    CONTEXT("context"), RATING("rating"), TIP_STATUS("tip-status"), TIP("tip"), FLAGS("flags"), REPORT("report")
}

internal class MiniAppActionFailure(message: String, val uncertain: Boolean = false) : Exception(message)

internal data class MiniAppRating(val average: Double, val count: Long, val mine: Int?) {
    companion object {
        fun parse(data: JSONObject): MiniAppRating {
            val average = (data.opt("rating_average") as? Number)?.toDouble()
            val count = (data.opt("rating_count") as? Number)?.toString()?.toLongOrNull()
            val mine = if (data.isNull("my_rating")) null else (data.opt("my_rating") as? Number)?.toString()?.toIntOrNull()
            require(average != null && average.isFinite() && average in 0.0..5.0 && count != null && count >= 0 &&
                (data.isNull("my_rating") || mine != null && mine in 1..5)) { "评分数据暂不可用" }
            return MiniAppRating(average, count, mine)
        }
    }
}

internal data class MiniAppActionContext(val id: String, val releaseId: Long?, val canRate: Boolean,
    val canTip: Boolean, val canReport: Boolean, val rating: MiniAppRating) {
    companion object {
        fun parse(data: JSONObject, id: String): MiniAppActionContext {
            val app = data.getJSONObject("app")
            require(app.getString("app_id") == id) { "应用信息与当前页面不符" }
            val release = (data.opt("release_id") as? Number)?.toString()?.toLongOrNull()?.takeIf { it > 0 }
            return MiniAppActionContext(id, release, app.opt("can_rate") == true, app.opt("can_tip") == true,
                app.opt("can_report") == true, MiniAppRating.parse(app))
        }
    }
}

internal data class MiniAppTipStatus(val amounts: List<Long>, val balance: Long, val nextTipAt: Instant?) {
    fun coolingDown(now: Instant = Instant.now()) = nextTipAt?.isAfter(now) == true
    fun allows(amount: Long?, now: Instant = Instant.now()) = amount != null && amount in amounts && amount <= balance && !coolingDown(now)
    companion object {
        fun parse(data: JSONObject): MiniAppTipStatus {
            val balance = (data.opt("balance") as? Number)?.toString()?.toLongOrNull()
            val array = data.getJSONArray("amounts")
            val amounts = (0 until array.length()).map {
                (array.opt(it) as? Number)?.toString()?.toLongOrNull()?.takeIf { value -> value > 0 }
                    ?: error("打赏金额暂不可用")
            }.distinct()
            require(balance != null && balance >= 0 && amounts.isNotEmpty() && amounts.size <= 20) { "打赏信息暂不可用" }
            val next = if (data.isNull("next_tip_at")) null else Instant.parse(data.getString("next_tip_at"))
            return MiniAppTipStatus(amounts, balance, next)
        }
    }
}

internal fun parseMiniAppFlags(data: JSONObject): FlagConfiguration {
    val minimum = data.getInt("minimum").also { require(it in 1..500) }
    val reasons = data.getJSONArray("reasons").objects().map {
        FlagReason(it.getInt("id").also { id -> require(id > 0) }, it.getString("key"), plainText(it.getString("name")),
            plainText(it.optString("description")), it.opt("require_message") == true)
    }.filterNot { it.contactAuthor }.distinctBy { it.id }
    return FlagConfiguration(reasons, minimum)
}
