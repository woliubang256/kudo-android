package com.kudo.android

import com.kudo.android.ui.apps.miniAppCompactCount
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniAppCountTest {
    @Test fun compactCountsKeepUnitsAndDoNotRoundUpOrOverflow() {
        for ((count, expected) in listOf(0L to "0", 9999L to "9999", 10_000L to "1万",
            12_345L to "1.2万", 99_999_999L to "9999.9万", 100_000_000L to "1亿",
            3_123_456_789L to "31.2亿", Long.MAX_VALUE to "92233720368.5亿")) {
            assertEquals(expected, miniAppCompactCount(count))
        }
    }
}
