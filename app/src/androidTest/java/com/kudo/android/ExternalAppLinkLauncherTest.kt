package com.kudo.android

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.data.ExternalLinkMode
import com.kudo.android.ui.ExternalAppLinkLauncher
import org.junit.Assert.*
import org.junit.Test

class ExternalAppLinkLauncherTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val share = "https://pan.quark.cn/s/Abc123?pwd=1234#files"
    private val scheme = "qklink://www.uc.cn/b20b84fd735a8dd3f7541129bacc4e9a?action=open_url&url=https%3A%2F%2Fpan.quark.cn%2Fs%2FAbc123"

    @Test fun systemOpeningPreservesUrlsAndLetsAndroidSelectTheHandler() {
        val started = mutableListOf<Intent>()
        val launcher = ExternalAppLinkLauncher(context, launch = { started += it })
        listOf("http://example.org/help?q=1#part", "https://share.test/s/abc", scheme).forEach {
            assertTrue(launcher.open(it))
            val intent = started.last()
            assertEquals(it, intent.dataString)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertNull(intent.`package`)
            assertNull(intent.component)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertFalse(intent.hasExtra(Intent.EXTRA_INITIAL_INTENTS))
        }
        assertEquals(3, started.size)
    }

    @Test fun quarkShareTargetsTheInstalledQuarkApp() {
        val started = mutableListOf<Intent>()
        val launcher = ExternalAppLinkLauncher(context, launch = { started += it })

        assertTrue(launcher.open(share))
        assertEquals("com.quark.browser", started.single().`package`)
        assertEquals(share, started.single().dataString)
    }

    @Test fun inAppModeNeverLaunchesEvenForQuarkSharesAndSupportedSchemes() {
        val launcher = ExternalAppLinkLauncher(context, launch = { fail("In-app mode must stay in Kudo") })
        listOf(share, scheme, "https://example.org/help").forEach {
            assertFalse(launcher.open(it, ExternalLinkMode.IN_APP))
        }
    }

    @Test fun siteAndSubdomainLinksStayInsideButLookalikeDomainsDoNot() {
        val started = mutableListOf<Intent>()
        val launcher = ExternalAppLinkLauncher(context, launch = { started += it })
        listOf("/t/123", "/guidelines", "https://linux.do/", "https://connect.linux.do/",
            "http://a.b.linux.do/help", "https://LINUX.DO/t/123").forEach { assertFalse(it, launcher.open(it)) }
        assertTrue(started.isEmpty())
        listOf("https://linux.do.evil.test/", "https://notlinux.do/").forEach { assertTrue(launcher.open(it)) }
        assertEquals(2, started.size)
    }

    @Test fun launchFailuresReturnControlToTheWebFallback() {
        listOf(ActivityNotFoundException(), SecurityException()).forEach { failure ->
            assertFalse(ExternalAppLinkLauncher(context, launch = { throw failure }).open(share))
        }
    }

    @Test fun unsafeSchemesAndCredentialsNeverLaunch() {
        val launcher = ExternalAppLinkLauncher(context, launch = { fail("Unsafe URL") })
        listOf("intent://example", "javascript:alert(1)", "file:///sdcard/test", "quark://arbitrary",
            "https://user:password@example.org/", "qklink://www.uc.cn/other?action=delete").forEach {
            assertFalse(it, launcher.open(it))
        }
    }
}
