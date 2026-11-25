package com.kudo.android

import com.kudo.android.ui.components.shouldBypassTopicSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicSnapshotCompatibilityTest {
    @Test fun matchesTheReportedDeviceAndBuild() {
        assertTrue(shouldBypassTopicSnapshot("Xiaomi", "dada", 36, "OS3.0.301.0.WOCCNXM"))
    }

    @Test fun manufacturerComparisonIgnoresCase() {
        assertTrue(shouldBypassTopicSnapshot("xiaomi", "dada", 36, "OS3.0.301.0.WOCCNXM"))
    }

    @Test fun otherManufacturersKeepCardSnapshots() {
        assertFalse(shouldBypassTopicSnapshot("Google", "dada", 36, "OS3.0.301.0.WOCCNXM"))
    }

    @Test fun otherXiaomiDevicesKeepCardSnapshots() {
        assertFalse(shouldBypassTopicSnapshot("Xiaomi", "other", 36, "OS3.0.301.0.WOCCNXM"))
    }

    @Test fun otherAndroidVersionsKeepCardSnapshots() {
        listOf(35, 37).forEach {
            assertFalse(shouldBypassTopicSnapshot("Xiaomi", "dada", it, "OS3.0.301.0.WOCCNXM"))
        }
    }

    @Test fun otherSystemBuildsKeepCardSnapshots() {
        listOf("OS3.0.302.0.WOCCNXM", "OS2.0.301.0.WOCCNXM", "").forEach {
            assertFalse(shouldBypassTopicSnapshot("Xiaomi", "dada", 36, it))
        }
    }
}
