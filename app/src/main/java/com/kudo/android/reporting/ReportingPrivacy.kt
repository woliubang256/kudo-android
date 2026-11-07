package com.kudo.android.reporting

import io.sentry.*
import io.sentry.protocol.*
import io.sentry.transport.ITransport

internal data class ReportingEnvironment(val release: String, val version: String, val build: String,
    val packageName: String, val osVersion: String, val osBuild: String, val manufacturer: String, val model: String)

/** Rebuild instead of clearing selected fields: new SDK fields cannot silently expand collection. */
internal class ReportingPrivacy(private val diagnostics: ReportingEnvironment) {
    fun event(source: SentryEvent): SentryEvent? {
        val exceptions = source.exceptions.orEmpty().take(16).map { original ->
            SentryException().apply {
                type = symbol(original.type) ?: "Error"
                module = symbol(original.module)
                stacktrace = original.stacktrace?.let { trace -> SentryStackTrace(trace.frames.orEmpty().takeLast(150).map { frame ->
                    SentryStackFrame().apply {
                        module = symbol(frame.module)
                        function = symbol(frame.function)
                        filename = frame.filename?.takeIf { it.matches(Regex("[A-Za-z0-9_.$-]+\\.(kt|java)")) }
                        lineno = frame.lineno
                        colno = frame.colno
                        isInApp = frame.isInApp
                    }
                }) }
                mechanism = Mechanism().apply { type = "generic"; isHandled = original.mechanism?.isHandled }
            }
        }
        if (exceptions.isEmpty()) return null // No free-form messages, logs or arbitrary payloads.
        return SentryEvent(source.timestamp).apply {
            eventId = source.eventId
            level = if (source.level == SentryLevel.FATAL) SentryLevel.FATAL else SentryLevel.ERROR
            platform = "java"
            release = diagnostics.release
            dist = diagnostics.build
            exceptions.let { setExceptions(it) }
            contexts.setApp(App().apply {
                appIdentifier = diagnostics.packageName; appVersion = diagnostics.version; appBuild = diagnostics.build
            })
            contexts.setOperatingSystem(OperatingSystem().apply {
                name = "Android"; version = diagnostics.osVersion; build = diagnostics.osBuild
            })
            contexts.setDevice(Device().apply { manufacturer = diagnostics.manufacturer; model = diagnostics.model })
        }
    }

    /** Applies again at the transport boundary, including SDK cached-envelope resends. */
    fun envelope(source: SentryEnvelope, serializer: ISerializer): SentryEnvelope? = runCatching {
        val item = source.items.singleOrNull { it.header.type == SentryItemType.Event } ?: return null
        val safe = item.getEvent(serializer)?.let(::event) ?: return null
        SentryEnvelope.from(serializer, safe, null)
    }.getOrNull()

    private fun symbol(value: String?): String? = value?.takeIf {
        it.length <= 250 && it.matches(Regex("[A-Za-z0-9_.$<>? -]+"))
    }
}

internal class PrivateReportingTransportFactory(private val privacy: ReportingPrivacy,
    private val delegate: ITransportFactory = AsyncHttpTransportFactory()) : ITransportFactory {
    override fun create(options: SentryOptions, requestDetails: RequestDetails): ITransport {
        val transport = delegate.create(options, requestDetails)
        return object : ITransport by transport {
            override fun send(envelope: SentryEnvelope, hint: Hint) {
                privacy.envelope(envelope, options.serializer)?.let { transport.send(it, hint) }
            }
        }
    }
}
