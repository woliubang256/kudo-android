package com.kudo.android.ui.web

import android.content.Context
import android.webkit.WebView

internal fun webViewProviderLabel(context: Context): String =
    WebView.getCurrentWebViewPackage(context)?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"