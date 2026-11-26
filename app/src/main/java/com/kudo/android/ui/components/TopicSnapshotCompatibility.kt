package com.kudo.android.ui.components

/** 单变量验证仅覆盖已取得崩溃证据的机型和系统构建，系统更新后重新验证。 */
internal fun shouldBypassTopicSnapshot(manufacturer: String, device: String, sdk: Int, incremental: String): Boolean =
    manufacturer.equals("Xiaomi", ignoreCase = true) && device == "dada" &&
        sdk == 36 && incremental == "OS3.0.301.0.WOCCNXM"
