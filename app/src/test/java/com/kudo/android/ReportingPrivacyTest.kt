package com.kudo.android

import com.kudo.android.reporting.*
import io.sentry.*
import io.sentry.protocol.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

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
}
