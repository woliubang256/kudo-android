package com.kudo.android.reporting

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReportingConsent { UNKNOWN, DENIED, ALLOWED }

internal interface ReportingConsentStore {
    fun read(): ReportingConsent
    fun write(value: ReportingConsent): Boolean
}

internal class PrivateReportingConsentStore(context: Context, name: String = "error_reporting") : ReportingConsentStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun read(): ReportingConsent = when (preferences.getString("consent", null)) {
        "allowed" -> ReportingConsent.ALLOWED
        "denied" -> ReportingConsent.DENIED
        else -> ReportingConsent.UNKNOWN
    }
    override fun write(value: ReportingConsent): Boolean = preferences.edit()
        .putString("consent", if (value == ReportingConsent.ALLOWED) "allowed" else "denied").commit()
}

/** Saved choice and this process's effective SDK state intentionally have different lifetimes. */
class ReportingController internal constructor(private val store: ReportingConsentStore, private val initialize: () -> Unit) {
    private val mutableConsent = MutableStateFlow(runCatching { store.read() }.getOrDefault(ReportingConsent.DENIED))
    val consent = mutableConsent.asStateFlow()
    private val mutablePromptOwner = MutableStateFlow<Any?>(null)
    internal val promptOwner = mutablePromptOwner.asStateFlow()
    private var attempted = false
    private var started = false
    @Volatile var active: Boolean = false
        private set

    @Synchronized
    fun start() {
        if (started) return
        started = true
        if (consent.value == ReportingConsent.ALLOWED) initializeOnce()
    }

    @Synchronized
    internal fun acquirePrompt(owner: Any): Boolean {
        if (consent.value != ReportingConsent.UNKNOWN || (promptOwner.value != null && promptOwner.value !== owner)) return false
        mutablePromptOwner.value = owner
        return true
    }

    @Synchronized
    internal fun releasePrompt(owner: Any) { if (promptOwner.value === owner) mutablePromptOwner.value = null }

    @Synchronized
    internal fun decide(owner: Any, allow: Boolean): Boolean {
        if (promptOwner.value !== owner || consent.value != ReportingConsent.UNKNOWN) return false
        if (!save(allow)) return false
        mutablePromptOwner.value = null
        if (allow) initializeOnce()
        return true
    }

    /** Settings changes only affect the next process, including when first consent is still unknown. */
    @Synchronized
    fun saveForNextStart(allow: Boolean): Boolean = save(allow)

    private fun save(allow: Boolean): Boolean {
        val next = if (allow) ReportingConsent.ALLOWED else ReportingConsent.DENIED
        if (!runCatching { store.write(next) }.getOrDefault(false)) return false
        mutableConsent.value = next
        return true
    }

    private fun initializeOnce() {
        if (attempted) return
        attempted = true
        active = runCatching { initialize() }.isSuccess
    }
}
