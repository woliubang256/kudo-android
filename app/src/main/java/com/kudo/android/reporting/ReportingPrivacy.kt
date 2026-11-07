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
                mechanism = Mechanism().apply {
                    type = original.mechanism?.type?.takeIf { it in setOf("ANR", "UncaughtExceptionHandler", "generic") } ?: "generic"
                    isHandled = original.mechanism?.isHandled
                }
            }
        }
        if (exceptions.isEmpty()) return null // No free-form messages, logs or arbitrary payloads.
        // Cached crashes may belong to the previous app version; retain only a validated release.
        val safeRelease = source.release?.takeIf {
            it.matches(Regex("${Regex.escape(diagnostics.packageName)}@[0-9A-Za-z.\\-]+\\+[0-9]+"))
        } ?: diagnostics.release
        val safeVersion = safeRelease.substringAfter('@').substringBeforeLast('+')
        val safeBuild = safeRelease.substringAfterLast('+')
        return SentryEvent(source.timestamp).apply {
            eventId = source.eventId
            level = if (source.level == SentryLevel.FATAL) SentryLevel.FATAL else SentryLevel.ERROR
            platform = "java"
            release = safeRelease
            dist = safeBuild
            exceptions.let { setExceptions(it) }
            val buildImages = source.debugMeta?.images.orEmpty().mapNotNull { original ->
                original.uuid?.takeIf { original.type == "proguard" && it.matches(Regex(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) }
                    ?.let { uuid -> DebugImage().apply { type = "proguard"; this.uuid = uuid } }
            }.take(1)
            if (buildImages.isNotEmpty()) debugMeta = DebugMeta().apply { images = buildImages }
            contexts.setApp(App().apply {
                appIdentifier = diagnostics.packageName; appVersion = safeVersion; appBuild = safeBuild
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
