package com.kudo.android

import com.kudo.android.reporting.*
import org.junit.Assert.*
import org.junit.Test

class ReportingConsentTest {
    private class Store(var value: ReportingConsent = ReportingConsent.UNKNOWN, var writable: Boolean = true) : ReportingConsentStore {
        override fun read() = value
        override fun write(value: ReportingConsent): Boolean { if (!writable) return false; this.value = value; return true }
    }

    @Test fun unknownAndDeniedNeverInitialize() {
        for (value in listOf(ReportingConsent.UNKNOWN, ReportingConsent.DENIED)) {
            var calls = 0
            val controller = ReportingController(Store(value)) { calls++ }
            repeat(3) { controller.start() }
            assertFalse(controller.active)
            assertEquals(0, calls)
        }
    }

    @Test fun firstAllowancePersistsBeforeInitializingExactlyOnce() {
        val store = Store()
        var calls = 0
        val controller = ReportingController(store) { assertEquals(ReportingConsent.ALLOWED, store.value); calls++ }
        val owner = Any()
        assertTrue(controller.acquirePrompt(owner))
        assertTrue(controller.decide(owner, true))
        repeat(3) { controller.start() }
        assertEquals(1, calls)
        assertTrue(controller.active)
        assertFalse(controller.acquirePrompt(Any()))
    }

    @Test fun declineIsRememberedAndNeverInitializes() {
        val store = Store()
        val controller = ReportingController(store) { fail("denied must not initialize") }
        val owner = Any()
        controller.acquirePrompt(owner)
        assertTrue(controller.decide(owner, false))
        assertEquals(ReportingConsent.DENIED, store.value)
        assertFalse(controller.acquirePrompt(Any()))
        controller.start()
    }

    @Test fun foregroundOwnersCannotShowOrResolveEachOthersPrompt() {
        val controller = ReportingController(Store()) { }
        val first = Any(); val second = Any()
        assertTrue(controller.acquirePrompt(first))
        assertFalse(controller.acquirePrompt(second))
        controller.releasePrompt(second)
        assertFalse(controller.decide(second, true))
        controller.releasePrompt(first)
        assertTrue(controller.acquirePrompt(second))
    }

    @Test fun failedPersistenceDoesNotChangeChoiceOrStartSdk() {
        val controller = ReportingController(Store(writable = false)) { fail("save failed") }
        val owner = Any(); controller.acquirePrompt(owner)
        assertFalse(controller.decide(owner, true))
        assertFalse(controller.saveForNextStart(true))
        assertEquals(ReportingConsent.UNKNOWN, controller.consent.value)
        assertSame(owner, controller.promptOwner.value)
    }

    @Test fun settingChangeOnlyTakesEffectInANewController() {
        for (initial in listOf(ReportingConsent.DENIED, ReportingConsent.ALLOWED)) {
            val store = Store(initial)
            var calls = 0
            val controller = ReportingController(store) { calls++ }
            controller.start()
            val active = controller.active
            assertTrue(controller.saveForNextStart(initial != ReportingConsent.ALLOWED))
            repeat(3) { controller.start() }
            assertEquals(active, controller.active)
            val next = ReportingController(store) { calls++ }; next.start()
            assertEquals(!active, next.active)
            assertEquals(1, calls)
        }
    }

    @Test fun initializationFailureDoesNotCrashOrRetryOnLifecycleReentry() {
        var calls = 0
        val controller = ReportingController(Store(ReportingConsent.ALLOWED)) { calls++; error("offline init") }
        repeat(3) { controller.start() }
        assertEquals(1, calls)
        assertFalse(controller.active)
        assertEquals(ReportingConsent.ALLOWED, controller.consent.value)
    }
}
