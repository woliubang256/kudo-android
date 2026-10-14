package com.kudo.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.ui.ExternalAppLinkLauncher
import com.kudo.android.ui.ExternalAppLinkPolicy
import org.junit.Assert.*
import org.junit.Test

class ExternalAppLinkLauncherTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val share = "https://pan.quark.cn/s/Abc123?pwd=1234#files"
    private fun candidate(pkg: String, host: String? = null) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.ShareActivity"; exported = true; enabled = true }
        filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme("https")
            host?.let { addDataAuthority(it, null) }
        }
    }

    @Test fun installedQuarkReceivesOriginalShareUrlEvenThoughItIsABrowser() {
        val started = mutableListOf<Intent>()
        val launcher = ExternalAppLinkLauncher(context, query = {
            if (it.`package` == ExternalAppLinkPolicy.QUARK_PACKAGE) listOf(candidate(ExternalAppLinkPolicy.QUARK_PACKAGE)) else emptyList()
        }, launch = { started += it })
        assertTrue(launcher.open(share))
        assertEquals(share, started.single().dataString)
        assertEquals(ExternalAppLinkPolicy.QUARK_PACKAGE, started.single().`package`)
        assertEquals(Intent.ACTION_VIEW, started.single().action)
        assertTrue(started.single().hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test fun matchingNativeAppUsesAnExplicitComponent() {
        val started = mutableListOf<Intent>()
        val launcher = ExternalAppLinkLauncher(context, query = {
            if (it.data?.host == "share.test") listOf(candidate("native.app", "share.test")) else emptyList()
        }, launch = { started += it })
        assertTrue(launcher.open("https://share.test/s/abc"))
        assertEquals("native.app.ShareActivity", started.single().component?.className)
    }

    @Test fun browserWithASeparateDomainFilterIsStillExcluded() {
        val launcher = ExternalAppLinkLauncher(context, query = {
            listOf(candidate("browser.app", it.data?.host?.takeUnless { host -> host.endsWith(".invalid") }))
        }, launch = { fail("Browser must not launch") })
        assertFalse(launcher.open("https://share.test/s/abc"))
    }

    @Test fun multipleMatchingAppsOnlyOfferTheFilteredCandidates() {
        var chooser: Intent? = null
        val launcher = ExternalAppLinkLauncher(context, query = {
            if (it.data?.host == "share.test") listOf(candidate("native.one", "share.test"),
                candidate("native.two", "share.test"), candidate(context.packageName, "share.test"), candidate("browser.app"))
            else listOf(candidate("browser.app"))
        }, launch = { chooser = it })
        assertTrue(launcher.open("https://share.test/s/abc"))
        assertEquals(Intent.ACTION_CHOOSER, chooser?.action)
        @Suppress("DEPRECATION")
        val primary = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        @Suppress("DEPRECATION")
        val others = chooser!!.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }
        assertEquals(listOf("native.one", "native.two"), (listOf(primary!!) + others).map { it.component?.packageName })
    }

    @Test fun missingAppsAndLaunchFailuresReturnControlToTheWebFallback() {
        assertFalse(ExternalAppLinkLauncher(context, query = { emptyList() }, launch = { fail() }).open(share))
        listOf(ActivityNotFoundException(), SecurityException()).forEach { failure ->
            assertFalse(ExternalAppLinkLauncher(context, query = {
                if (it.`package` != null) listOf(candidate(ExternalAppLinkPolicy.QUARK_PACKAGE)) else emptyList()
            }, launch = { throw failure }).open(share))
        }
    }

    @Test fun sitePagesAndUnsafeSchemesDoNotQueryOrLaunchOtherApps() {
        val launcher = ExternalAppLinkLauncher(context, query = { fail("Site links must stay in Kudo"); emptyList() }, launch = { fail() })
        listOf("/t/123", "/guidelines", "https://linux.do/", "intent://example", "javascript:alert(1)").forEach {
            assertFalse(it, launcher.open(it))
        }
    }

    @Test fun actualPackageVisibilityFindsQuarkOnThisDevice() {
        // Device acceptance case; no third-party data is read or changed.
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("quarkInstalled") == "true")
        var opened: Intent? = null
        assertTrue(ExternalAppLinkLauncher(context, launch = { opened = it }).open(share))
        assertEquals(ExternalAppLinkPolicy.QUARK_PACKAGE, opened?.`package`)
        assertEquals(share, opened?.dataString)
    }
}
