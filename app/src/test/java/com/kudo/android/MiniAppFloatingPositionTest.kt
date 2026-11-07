package com.kudo.android

import androidx.compose.ui.unit.*
import com.kudo.android.ui.web.MiniAppFloatingPosition
import com.kudo.android.ui.web.MiniAppFloatingSide
import org.junit.Assert.*
import org.junit.Test

class MiniAppFloatingPositionTest {
    @Test fun expandingRetainsHandleCenterAndPhysicalSideInBothDirections() {
        val anchor = IntRect(20, 30, 420, 830)
        for (direction in LayoutDirection.entries) for (side in MiniAppFloatingSide.entries) {
            val handle = MiniAppFloatingPosition(side, .46f, IntRect.Zero, 48, 0)
                .calculatePosition(anchor, IntSize(500, 900), direction, IntSize(48, 48))
            val card = MiniAppFloatingPosition(side, .46f, IntRect.Zero, 48, 8)
                .calculatePosition(anchor, IntSize(500, 900), direction, IntSize(232, 168))
            assertEquals(handle.y + 24, card.y + 84)
            assertEquals(if (side == MiniAppFloatingSide.LEFT) 28 else 180, card.x)
        }
    }

    @Test fun draggedPanelsStayWithinCutoutsAndKeyboardOnSmallWindows() {
        val anchor = IntRect(0, 0, 320, 480)
        val safe = IntRect(12, 24, 18, 200)
        for (side in MiniAppFloatingSide.entries) for (fraction in listOf(-1f, 0f, .46f, 1f, 2f)) {
            val position = MiniAppFloatingPosition(side, fraction, safe, 48, 8)
                .calculatePosition(anchor, IntSize(320, 480), LayoutDirection.Ltr, IntSize(260, 168))
            assertTrue(position.x >= 12 && position.x + 260 <= 302)
            assertTrue(position.y >= 24 && position.y + 168 <= 280)
        }
    }

    @Test fun oversizeContentAndFullyOccludedWindowHaveStableOrigin() {
        val anchor = IntRect(0, 0, 100, 100)
        for (side in MiniAppFloatingSide.entries) {
            val position = MiniAppFloatingPosition(side, .46f, IntRect(10, 20, 10, 90), 48, 8)
                .calculatePosition(anchor, IntSize(100, 100), LayoutDirection.Rtl, IntSize(200, 200))
            assertEquals(IntOffset(10, 20), position)
        }
    }
}
