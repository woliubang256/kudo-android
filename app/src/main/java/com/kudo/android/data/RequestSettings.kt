package com.kudo.android.data

import android.content.Context
import androidx.core.content.edit
import com.kudo.android.network.RequestCooldown
import com.kudo.android.network.RequestLimits
import com.kudo.android.network.RequestScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal fun encodeRequestLimits(value: RequestLimits): String = JSONObject().put("version", 1)
    .put("concurrent", value.concurrent).put("perWindow", value.perWindow).put("windowSeconds", value.windowSeconds).toString()

internal fun decodeRequestLimits(raw: String?): RequestLimits = runCatching {
    val json = JSONObject(raw.orEmpty())
    require(json.opt("version") == 1)
    fun read(key: String, range: IntRange, default: Int) = (json.opt(key) as? Int)?.takeIf { it in range } ?: default
    RequestLimits(read("concurrent", 1..10, 3), read("perWindow", 2..30, 6), read("windowSeconds", 1..10, 3))
}.getOrDefault(RequestLimits())

class RequestSettings(context: Context, cooldown: RequestCooldown = RequestCooldown()) {
    private val prefs = context.getSharedPreferences("kudo_network", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(decodeRequestLimits(prefs.getString("request_limits", null)))
    val configuration = state.asStateFlow()
    val scheduler = RequestScheduler(state.value, cooldown)

    @Synchronized fun update(value: RequestLimits) {
        prefs.edit { putString("request_limits", encodeRequestLimits(value)) }
        scheduler.update(value)
        state.value = value
    }
}
