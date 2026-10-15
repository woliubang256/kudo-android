package com.kudo.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.kudo.android.data.ExternalLinkMode

internal class ExternalAppLinkLauncher(
    private val context: Context,
    private val launch: (Intent) -> Unit = context::startActivity,
) {
    /** Let Android choose the browser or linked app; false keeps the in-app web fallback. */
    fun open(value: String, mode: ExternalLinkMode = ExternalLinkMode.SYSTEM): Boolean {
        if (mode != ExternalLinkMode.SYSTEM) return false
        val url = ExternalAppLinkPolicy.contentUrl(value) ?: return false
        if (ExternalAppLinkPolicy.isSiteUrl(url)) return false
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { ExternalAppLinkPolicy.targetPackage(url)?.let(::setPackage) }
        return try {
            launch(intent)
            true
        } catch (_: ActivityNotFoundException) { false }
          catch (_: SecurityException) { false }
    }
}
