package com.kudo.android.reporting

import android.app.Application
import android.os.Build
import com.kudo.android.BuildConfig
import io.sentry.Sentry
import io.sentry.UncaughtExceptionHandlerIntegration
import io.sentry.android.core.AnrIntegration
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import java.io.File

internal object ErrorReporting {
    private const val DSN = "https://12320543a69b0e4d7f8382a0f98bfbb9@o4512391547912192.ingest.us.sentry.io/4512391557021696"

    fun controller(application: Application): ReportingController = ReportingController(PrivateReportingConsentStore(application)) {
        initialize(application)
    }

    internal fun initialize(application: Application, dsn: String = DSN, cacheDirectory: File? = null): SentryAndroidOptions {
        val privacy = ReportingPrivacy(ReportingEnvironment(
            "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), BuildConfig.APPLICATION_ID,
            Build.VERSION.RELEASE, Build.VERSION.INCREMENTAL, Build.MANUFACTURER, Build.MODEL))
        var configured: SentryAndroidOptions? = null
        SentryAndroid.init(application) { options ->
            configure(options, privacy, dsn)
            if (cacheDirectory != null) options.cacheDirPath = cacheDirectory.absolutePath
            // Do not read pre-consent historical ANRs or collect lifecycle/user activity.
            options.integrations.removeAll { it !is UncaughtExceptionHandlerIntegration &&
                it.javaClass.name != "io.sentry.android.core.SendCachedEnvelopeIntegration" }
            options.addIntegration(AnrIntegration(application))
            configured = options
        }
        if (configured == null || !Sentry.isEnabled()) {
            Sentry.close()
            error("Reporting initialization failed")
        }
        return checkNotNull(configured)
    }

    internal fun configure(options: SentryAndroidOptions, privacy: ReportingPrivacy, dsn: String = DSN) {
        // Install the outbound privacy boundary before any fallible optional configuration.
        options.setBeforeSend { event, _ -> privacy.event(event) }
        options.setBeforeSendTransaction { _, _ -> null }
        options.setTransportFactory(PrivateReportingTransportFactory(privacy))
        options.dsn = dsn
        options.isDebug = false
        options.isEnableExternalConfiguration = false
        options.isSendDefaultPii = false
        options.isEnableNdk = false
        options.isEnableScopeSync = false
        options.isEnableNdkAppHangTracking = false
        options.isEnableRootCheck = false
        options.isAttachScreenshot = false
        options.isAttachViewHierarchy = false
        options.isAttachAnrThreadDump = false
        options.isAttachRawTombstone = false
        options.isAttachThreads = false
        options.isAttachServerName = false
        options.isSendModules = false
        options.isEnableAutoSessionTracking = false
        options.isEnableScopePersistence = false
        options.isEnableScreenTracking = false
        options.isSendClientReports = false
        options.isEnableActivityLifecycleBreadcrumbs = false
        options.isEnableAppLifecycleBreadcrumbs = false
        options.isEnableSystemEventBreadcrumbs = false
        options.isEnableAppComponentBreadcrumbs = false
        options.isEnableNetworkEventBreadcrumbs = false
        options.isEnableUserInteractionBreadcrumbs = false
        options.maxBreadcrumbs = 0
        options.setBeforeBreadcrumb { _, _ -> null }
        options.tracesSampleRate = 0.0
        options.profilesSampleRate = 0.0
        options.profileSessionSampleRate = 0.0
        options.isEnableAppStartProfiling = false
        options.isEnableAutoActivityLifecycleTracing = false
        options.isEnableUserInteractionTracing = false
        options.isEnableTimeToFullDisplayTracing = false
        options.isEnableFramesTracking = false
        options.isEnablePerformanceV2 = false
        options.isEnableStandaloneAppStartTracing = false
        options.isEnableAutoTraceIdGeneration = false
        options.isEnableBackpressureHandling = false
        options.isEnableDatabaseTransactionTracing = false
        options.isEnableCacheTracing = false
        options.isEnableQueueTracing = false
        options.logs.isEnabled = false
        options.metrics.isEnabled = false
        options.sessionReplay.sessionSampleRate = 0.0
        options.sessionReplay.onErrorSampleRate = 0.0
    }
}
