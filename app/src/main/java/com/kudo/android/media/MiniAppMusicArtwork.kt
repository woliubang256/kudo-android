package com.kudo.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/** Only the bounded, already rendered thumbnail crosses the sandbox boundary. */
internal object MiniAppMusicArtwork {
    private const val PREFIX = "data:image/jpeg;base64,"
    fun decode(value: String): Bitmap? = runCatching {
        if (value.length !in (PREFIX.length + 4)..100000 || !value.startsWith(PREFIX)) return null
        val encoded = value.substring(PREFIX.length)
        if (!encoded.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512) return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
