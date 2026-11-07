package com.kudo.android

import com.kudo.android.ui.web.*
import org.junit.Assert.*
import org.junit.Test

class MiniAppMusicStateTest {
    @Test fun onlyExplicitlyApprovedAppUsesMusicAdapterRegardlessOfAccount() {
        assertTrue(MiniAppTaskKey(null, MUSIC_APP_ID).supportsMusic())
        assertTrue(MiniAppTaskKey(101, MUSIC_APP_ID).supportsMusic())
        assertFalse(MiniAppTaskKey(101,"01a1966a-f4a0-7b43-ad9c-c46b7f0e0281").supportsMusic())
        assertFalse(MiniAppTaskKey(101,MUSIC_APP_ID + "x").supportsMusic())
    }
}
