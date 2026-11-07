package com.kudo.android

import com.kudo.android.reporting.*
import io.sentry.*
import io.sentry.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.GzipSource
import okio.buffer
import io.sentry.android.core.SentryAndroidOptions

class ReportingPrivacyTest {
    private val privacy = ReportingPrivacy(ReportingEnvironment("com.test@1+1", "1", "1", "com.test", "13", "build", "test", "phone"))
    private val options = SentryOptions()
    private val secret = "PRIVATE_COOKIE_EDITOR_TEXT"

    private fun unsafeEvent() = SentryEvent().apply {
        user = User().apply { username = secret; ipAddress = "127.0.0.1" }
        request = Request().apply { url = "https://secret.example/$secret"; data = secret }
        message = Message().apply { formatted = secret }
        setExtra("editor", secret); setTag("account", secret)
        addBreadcrumb(Breadcrumb(secret))
        contexts["private"] = secret
        exceptions = listOf(SentryException().apply {
            type = "IllegalStateException"; module = "java.lang"; value = secret
            stacktrace = SentryStackTrace(listOf(SentryStackFrame().apply {
                module = "com.test.Screen"; function = "render"; filename = "Screen.kt"; lineno = 42
                absPath = "/data/$secret"; vars = mapOf("input" to secret); contextLine = secret
            }))
        })
    }

    @Test fun reconstructsOnlyDiagnosticFields() {
        val safe = privacy.event(unsafeEvent())!!
        val encoded = StringWriter().also { options.serializer.serialize(safe, it) }.toString()
        assertFalse(encoded, encoded.contains(secret))
        assertNull(safe.user); assertNull(safe.request); assertNull(safe.message)
        assertTrue(safe.breadcrumbs.isNullOrEmpty())
        assertEquals("IllegalStateException", safe.exceptions!!.single().type)
        assertEquals(42, safe.exceptions!!.single().stacktrace!!.frames!!.single().lineno)
        assertEquals("phone", safe.contexts.device!!.model)
        assertEquals("com.test@1+1", safe.release)
    }

    @Test fun transportDropsAttachmentsAndCachedUnfilteredFields() {
        val original = SentryEnvelope(unsafeEvent().eventId, null, listOf(
            SentryEnvelopeItem.fromEvent(options.serializer, unsafeEvent()),
            SentryEnvelopeItem.fromAttachment(options.serializer, options.logger, Attachment(secret.toByteArray(), "secret.txt"), 1024)))
        val safe = privacy.envelope(original, options.serializer)!!
        assertEquals(1, safe.items.count())
        val encoded = StringWriter().also { options.serializer.serialize(safe, it) }.toString()
        assertFalse(encoded, encoded.contains(secret))
        assertFalse(encoded, encoded.contains("\"type\":\"attachment\""))
    }

    @Test fun nonErrorPayloadsAreDropped() {
        assertNull(privacy.event(SentryEvent().apply { message = Message().apply { formatted = secret } }))
        assertNull(privacy.envelope(SentryEnvelope(null, null, emptyList()), options.serializer))
    }

    @Test fun cachedCrashesKeepTheirValidatedOriginalVersion() {
        val safe = privacy.event(unsafeEvent().apply { release = "com.test@0.9.0+12" })!!
        assertEquals("com.test@0.9.0+12", safe.release)
        assertEquals("0.9.0", safe.contexts.app!!.appVersion)
        assertEquals("12", safe.dist)
        assertEquals("com.test@1+1", privacy.event(unsafeEvent().apply { release = "https://private/$secret" })!!.release)
    }

    @Test fun keepsKnownErrorMechanismsAndBuildUuidWithoutPrivateMetadata() {
        val uuid = "bd75d63c-5845-4c46-aec2-4b4e25e05484"
        for (kind in listOf("ANR", "UncaughtExceptionHandler")) {
            val source = unsafeEvent().apply {
                exceptions!!.single().mechanism = Mechanism().apply {
                    type = kind; isHandled = false; data = mapOf("private" to secret); description = secret
                }
                debugMeta = DebugMeta().apply { images = listOf(DebugImage().apply {
                    type = "proguard"; this.uuid = uuid; debugFile = secret; codeFile = secret
                }, DebugImage().apply { type = "elf"; codeFile = secret }) }
            }
            val safe = privacy.event(source)!!
            assertEquals(kind, safe.exceptions!!.single().mechanism!!.type)
            assertEquals(false, safe.exceptions!!.single().mechanism!!.isHandled)
            assertEquals(uuid, safe.debugMeta!!.images!!.single().uuid)
            val encoded = StringWriter().also { options.serializer.serialize(safe, it) }.toString()
            assertFalse(encoded, encoded.contains(secret))
        }
    }

    @Test fun actualHttpTransportSendsOnlySafeErrorToLocalReceiver() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val localOptions = SentryAndroidOptions()
            ErrorReporting.configure(localOptions, privacy, "http://public@localhost:${server.port}/1")
            val transport = localOptions.transportFactory.create(localOptions, RequestDetails(server.url("/api/1/envelope/").toString(), emptyMap()))
            try {
                transport.send(SentryEnvelope.from(localOptions.serializer, unsafeEvent(), null))
                transport.flush(5000)
                val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("No local envelope")
                val payload = if (request.getHeader("Content-Encoding") == "gzip") GzipSource(request.body).buffer().readUtf8() else request.body.readUtf8()
                assertTrue(payload, payload.contains("IllegalStateException"))
                assertFalse(payload, payload.contains(secret))
                assertFalse(payload, payload.contains("breadcrumbs"))
                assertFalse(payload, payload.contains("request"))
                assertEquals("/api/1/envelope/", request.path)
            } finally { transport.close(); localOptions.executorService.close(1000) }
        }
    }
}
