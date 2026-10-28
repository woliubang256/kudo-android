package com.kudo.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ImageLibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences("kudo_image_library", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    private fun accountKey(accountId: Int?): String {
        require(accountId == null || accountId > 0)
        return accountId?.let { "user_$it" } ?: "guest"
    }

    suspend fun load(accountId: Int?): ImageLibrary = withContext(Dispatchers.IO) {
        mutex.withLock { decodeImageLibrary(preferences.getString(accountKey(accountId), null)) }
    }

    suspend fun update(accountId: Int?, transform: (ImageLibrary) -> ImageLibrary): ImageLibrary = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = accountKey(accountId)
            val old = decodeImageLibrary(preferences.getString(key, null))
            val updated = transform(old)
            if (old != updated) {
                check(preferences.edit().putString(key, encodeImageLibrary(updated)).commit()) { "图库保存失败，请检查存储空间后重试" }
                changes.value++
            }
            updated
        }
    }
}
