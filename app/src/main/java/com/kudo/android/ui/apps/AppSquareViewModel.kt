package com.kudo.android.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.MiniAppDirectoryPage
import com.kudo.android.data.MiniAppEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppSquareState(
    val query: String = "", val apps: List<MiniAppEntry> = emptyList(),
    val loading: Boolean = true, val hasMore: Boolean = false, val page: Int = -1,
    val error: Throwable? = null, val moreError: Throwable? = null, val revision: Int = 0,
)

class AppSquareViewModel(private val load: suspend (String, Int) -> MiniAppDirectoryPage) : ViewModel() {
    private val _state = MutableStateFlow(AppSquareState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    init { request() }

    fun search(query: String) {
        if (query == _state.value.query) return
        generation++; job?.cancel()
        _state.value = AppSquareState(query = query, revision = _state.value.revision + 1)
        request(debounce = true)
    }

    fun retry() = request()
    fun more() {
        val current = _state.value
        if (current.loading || !current.hasMore || current.error != null) return
        request(more = true)
    }

    private fun request(more: Boolean = false, debounce: Boolean = false) {
        job?.cancel()
        val token = ++generation
        val old = _state.value
        val page = if (more) old.page + 1 else 0
        _state.update { it.copy(loading = true, error = null, moreError = null) }
        job = viewModelScope.launch {
            try {
                if (debounce) delay(300)
                val result = withContext(Dispatchers.IO) { load(old.query.trim(), page) }
                if (generation == token) _state.update { it.copy(
                    apps = (if (more) it.apps + result.apps else result.apps).distinctBy(MiniAppEntry::id),
                    loading = false, page = page, hasMore = result.hasMore,
                ) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == token) _state.update {
                    if (more) it.copy(loading = false, moreError = e) else it.copy(loading = false, error = e)
                }
            }
        }
    }
}
