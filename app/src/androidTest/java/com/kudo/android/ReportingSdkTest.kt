package com.kudo.android

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.kudo.android.reporting.ErrorReporting
import io.sentry.*
import io.sentry.protocol.Request
import io.sentry.protocol.User
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.GzipSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class ReportingSdkTest {
    @Test fun realManualSdkOnlyEmitsSanitizedErrorsToTheLocalReceiver() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val realConsent = context.getSharedPreferences("error_reporting", Context.MODE_PRIVATE).all.toMap()
        val cache = File(context.cacheDir, "sentry-sdk-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val secret = "PRIVATE_ACCOUNT_COOKIE_EDITOR_CONTENT"
        assertFalse(Sentry.isEnabled())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            try {
                val options = ErrorReporting.initialize(context.applicationContext as Application,
                    "http://public@127.0.0.1:${server.port}/1", cache)
                assertTrue(Sentry.isEnabled())
                assertTrue(options.integrations.all { it.javaClass.simpleName in setOf(
                    "UncaughtExceptionHandlerIntegration", "SendCachedEnvelopeIntegration", "AnrIntegration") })
                assertFalse(options.isEnableAutoSessionTracking)
                assertEquals(0, options.maxBreadcrumbs)
                assertFalse(options.isAttachScreenshot)
                Sentry.addBreadcrumb(Breadcrumb(secret))
                Sentry.configureScope { scope -> scope.user = User().apply { username = secret } }
                val event = SentryEvent(IllegalStateException(secret)).apply {
                    request = Request().apply { url = "https://private.invalid/$secret"; data = secret }
                    setExtra("editor", secret)
                }
                Sentry.captureEvent(event, Hint().apply { addAttachment(Attachment(secret.toByteArray(), "private.txt")) })
                Sentry.flush(5000)
                val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("No local error event")
                assertEquals("/api/1/envelope/", request.path)
                val payload = if (request.getHeader("Content-Encoding") == "gzip") GzipSource(request.body).buffer().readUtf8() else request.body.readUtf8()
                assertTrue(payload, payload.contains("IllegalStateException"))
                assertTrue(payload, payload.contains(BuildConfig.VERSION_NAME))
                for (removed in listOf(secret, "\"user\"", "\"request\"", "\"breadcrumbs\"", "\"attachment\"", "private.txt"))
                    assertFalse("Unexpected $removed in $payload", payload.contains(removed))
                assertNull("Tracing, sessions and metrics must not create extra envelopes", server.takeRequest(300, TimeUnit.MILLISECONDS))
                assertEquals(realConsent, context.getSharedPreferences("error_reporting", Context.MODE_PRIVATE).all)
            } finally {
                Sentry.close()
                cache.deleteRecursively()
            }
        }
        assertFalse(Sentry.isEnabled())
    }
}
