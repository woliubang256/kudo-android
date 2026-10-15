package com.kudo.android

import com.kudo.android.data.*
import com.kudo.android.network.RequestLimits
import org.junit.Assert.*
import org.junit.Test

class RequestSettingsTest {
    @Test fun legacyMissingOrUnknownFormatsUseBalancedDefaults() {
        for (raw in listOf(null, "invalid", "{}", """{"version":2,"concurrent":9}"""))
            assertEquals(RequestLimits.Balanced, decodeRequestLimits(raw))
    }

    @Test fun presetsAndCustomValuesRoundTripIndependentlyOfDohConfiguration() {
        for (limits in listOf(RequestLimits.Gentle, RequestLimits.Balanced, RequestLimits.Turbo, RequestLimits(10, 2, 10)))
            assertEquals(limits, decodeRequestLimits(encodeRequestLimits(limits)))
    }

    @Test fun corruptFieldsRepairIndividuallyWithoutDiscardingValidChoices() {
        assertEquals(RequestLimits(7, 6, 9), decodeRequestLimits("""{"version":1,"concurrent":7,"perWindow":0,"windowSeconds":9}"""))
        for (value in listOf("-1", "11", "null", "\"3\"", "3.5", "true")) {
            assertEquals(RequestLimits(3, 20, 8), decodeRequestLimits("""{"version":1,"concurrent":$value,"perWindow":20,"windowSeconds":8}"""))
        }
    }
}
