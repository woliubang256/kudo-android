package com.kudo.android.ui.web

import android.graphics.Bitmap

internal data class MiniAppFloatingEntry(
    val request: MiniAppTaskRequest,
    val taskId: Int,
    val info: MiniAppInfo?,
    val preview: Bitmap?,
)
