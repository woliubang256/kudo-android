package com.kudo.android.ui.web

internal const val MUSIC_APP_ID = "01a1a755-8210-7a3f-877b-6b09df8dce08"
internal fun MiniAppTaskKey.supportsMusic() = appId == MUSIC_APP_ID

internal data class MiniAppMusicState(val title: String = "", val artist: String = "", val playing: Boolean = false,
    val canControl: Boolean = false, val canSkip: Boolean = false)
