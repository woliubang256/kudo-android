package com.kudo.android.ui.library

import androidx.compose.runtime.*
import com.kudo.android.data.*
import kotlinx.coroutines.CancellationException

internal val LocalImageLibrary = staticCompositionLocalOf<ImageLibraryState?> { null }
internal val LocalImageSource = staticCompositionLocalOf<ImageSource?> { null }

@Stable
internal class ImageLibraryState(val store: ImageLibraryStore, val accountId: Int?) {
    var library by mutableStateOf<ImageLibrary?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    suspend fun reload() {
        try { library = store.load(accountId); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "图库读取失败，请重试；原有数据不会被覆盖" }
    }

    suspend fun update(transform: (ImageLibrary) -> ImageLibrary): ImageLibrary = store.update(accountId, transform).also {
        library = it
        error = null
    }
}

@Composable
internal fun ImageLibraryEnvironment(store: ImageLibraryStore, accountId: Int?, ready: Boolean, content: @Composable () -> Unit) {
    val state = remember(store, accountId, ready) { if (ready) ImageLibraryState(store, accountId) else null }
    val revision by store.revision.collectAsState()
    LaunchedEffect(state, revision) { state?.reload() }
    CompositionLocalProvider(LocalImageLibrary provides state, content = content)
}
