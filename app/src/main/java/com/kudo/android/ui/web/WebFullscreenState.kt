package com.kudo.android.ui.web

import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class WebFullscreenState {
    var view by mutableStateOf<View?>(null)
        private set
    private var callback: WebChromeClient.CustomViewCallback? = null

    fun show(content: View, callback: WebChromeClient.CustomViewCallback) {
        if (view != null) { callback.onCustomViewHidden(); return }
        this.callback = callback
        view = content
    }

    fun exit() {
        val content = view ?: return
        view = null
        (content.parent as? ViewGroup)?.removeView(content)
        val hidden = callback
        callback = null
        hidden?.onCustomViewHidden()
    }
}
