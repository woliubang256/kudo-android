package com.kudo.android.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class ExternalAppLinkLauncher(
    private val context: Context,
    private val query: (Intent) -> List<ResolveInfo> = { intent ->
        context.packageManager.queryIntentActivities(intent,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_ALL or PackageManager.GET_RESOLVED_FILTER)
    },
    private val launch: (Intent) -> Unit = context::startActivity,
) {
    /** Returns false so the caller can keep browsing when no app can handle the URL. */
    fun open(value: String): Boolean {
        val url = ExternalAppLinkPolicy.contentUrl(value) ?: return false
        if (url.toHttpUrlOrNull()?.host == "linux.do") return false
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (ExternalAppLinkPolicy.quarkShare(url) != null || ExternalAppLinkPolicy.quarkScheme(url) != null) {
            val quark = Intent(view).setPackage(ExternalAppLinkPolicy.QUARK_PACKAGE)
            if (query(quark).isNotEmpty() && start(quark)) return true
            // Custom schemes must never be handed to a different application.
            if (ExternalAppLinkPolicy.quarkScheme(url) != null) return false
        }
        val browsers = listOf("https", "http").flatMap { scheme ->
            query(Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://app-link-browser-probe.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        }.mapNotNull { it.activityInfo?.packageName }.toSet()
        val candidates = appLinkCandidates(query(view).mapNotNull { result ->
            result.activityInfo?.takeIf { it.exported && it.enabled }?.let {
                AppLinkCandidate(it.packageName, it.name,
                    it.packageName in browsers || (result.filter?.countDataAuthorities() ?: 0) == 0)
            }
        }, context.packageName)
        val intents = candidates.map { candidate ->
            Intent(view).setComponent(ComponentName(candidate.packageName, candidate.activityName))
        }
        if (intents.isEmpty()) return false
        val intent = if (intents.size == 1) intents.single() else
            Intent.createChooser(intents.first(), "选择应用打开").putExtra(
                Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
        return start(intent)
    }

    private fun start(intent: Intent): Boolean = try {
        launch(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
}
