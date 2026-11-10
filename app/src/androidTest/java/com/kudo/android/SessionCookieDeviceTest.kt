package com.kudo.android

import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import com.kudo.android.data.AndroidSessionCookies
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Uses a reserved fixture origin only; never clears or reads the user's real site cookies. */
class SessionCookieDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val origin = "https://kudo-session-fixture.invalid/"
    private val raw = "_t=fixture-persistent; Path=/; Secure; HttpOnly; Max-Age=3600; SameSite=Lax"

    @Before fun initializeWebViewStore() {
        instrumentation.runOnMainSync { CookieManager.getInstance().setAcceptCookie(true) }
    }

    @Test fun wrappersShareTheSessionGenerationAndRejectLateResponses() {
        val first = AndroidSessionCookies()
        val second = AndroidSessionCookies()
        try {
            first.set(origin, raw)
            val old = first.snapshot(origin)
            second.invalidate()
            assertFalse(first.isCurrent(old))
            assertFalse(first.receive(origin, listOf("_t=stale; Path=/; Secure; Max-Age=3600"), old))
            assertTrue(second.get(origin).contains("_t=fixture-persistent"))
        } finally { first.set(origin, "_t=; Path=/; Secure; Max-Age=0") }
    }

    @Test fun browserLoginCannotBeOverwrittenByAnOlderNativeResponse() {
        val cookies = AndroidSessionCookies()
        try {
            cookies.set(origin, raw)
            val old = cookies.snapshot(origin)
            val written = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(origin,
                    "_t=browser-login; Path=/; Secure; HttpOnly; Max-Age=3600") { written.countDown() }
            }
            assertTrue(written.await(5, java.util.concurrent.TimeUnit.SECONDS))
            cookies.receive(origin, listOf("_t=; Path=/; Max-Age=0"), old)
            assertTrue(cookies.get(origin).contains("_t=browser-login"))
        } finally { cookies.set(origin, "_t=; Path=/; Secure; Max-Age=0") }
    }

    @Test fun seedPersistentCookieForColdStart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sessionCookiePhase") == "seed")
        val cookies = AndroidSessionCookies()
        assertTrue(cookies.receive(origin, listOf(raw), cookies.snapshot(origin)))
        assertTrue(cookies.get(origin).contains("_t=fixture-persistent"))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            val metadata = CookieManagerCompat.getCookieInfo(CookieManager.getInstance(), origin)
                .mapNotNull { Cookie.parse(origin.toHttpUrl(), it) }.first { it.name == "_t" }
            assertTrue(metadata.persistent)
            assertTrue(metadata.httpOnly)
            assertTrue(metadata.secure)
            // getCookieInfo includes Domain even for host-only cookies; verify actual scope instead.
            assertFalse(cookies.get("https://child.kudo-session-fixture.invalid/").contains("_t="))
        }
        instrumentation.targetContext.getSharedPreferences("session-cookie-fixture", 0).edit()
            .putInt("seed-pid", android.os.Process.myPid()).commit()
    }

    /** Run in a separate instrumentation process after seedPersistentCookieForColdStart. */
    @Test fun verifyPersistentCookieAfterColdStart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sessionCookiePhase") == "verify")
        val cookies = AndroidSessionCookies()
        val preferences = instrumentation.targetContext.getSharedPreferences("session-cookie-fixture", 0)
        try {
            val seedPid = preferences.getInt("seed-pid", -1)
            assertTrue("The seed phase must have completed", seedPid > 0)
            assertNotEquals(seedPid, android.os.Process.myPid())
            assertTrue(cookies.get(origin).contains("_t=fixture-persistent"))
        } finally {
            cookies.set(origin, "_t=; Path=/; Secure; Max-Age=0")
            preferences.edit().clear().commit()
        }
    }
}
