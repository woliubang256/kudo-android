package com.kudo.android.ui.web

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap

internal object MiniAppShortcuts {
    suspend fun pin(context: Context, request: MiniAppTaskRequest, info: MiniAppInfo?): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) return false
        val bitmap = info?.iconUrl?.let { url -> runCatching {
            SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).size(192).build()).image?.toBitmap()
        }.getOrNull() }
        val name = info?.name?.takeIf { it.isNotBlank() } ?: "小程序"
        val shortcut = ShortcutInfo.Builder(context, request.key.shortcutId).setShortLabel(name.take(40)).setLongLabel(name)
            .setIcon(bitmap?.let(Icon::createWithBitmap) ?: Icon.createWithResource(context, com.kudo.android.R.drawable.ic_kudo))
            .setIntent(MiniAppTasks.intent(context, request).putExtra(MiniAppTasks.SOURCE_TASK, -1)).build()
        return manager.requestPinShortcut(shortcut, null)
    }
}
