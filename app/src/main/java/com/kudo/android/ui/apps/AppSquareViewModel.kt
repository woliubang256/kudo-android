package com.kudo.android.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.MiniAppDirectoryPage
import com.kudo.android.data.MiniAppEntry
import com.kudo.android.data.MiniAppDetails
import com.kudo.android.data.MiniAppSort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppSquareState(
    val query: String = "", val apps: List<MiniAppEntry> = emptyList(),
    val loading: Boolean = true, val hasMore: Boolean = false, val page: Int = -1,
    val error: Throwable? = null, val moreError: Throwable? = null, val revision: Int = 0,
    val sort: MiniAppSort = MiniAppSort.LATEST, val about: AppSquareAboutState? = null,
)

data class AppSquareAboutState(val app: MiniAppEntry, val info: MiniAppDetails? = null,
    val loading: Boolean = true, val error: Throwable? = null)

class AppSquareViewModel(
    private val loadInfo: suspend (String) -> MiniAppDetails = { error("应用信息暂不可用") },
    private val load: suspend (String, Int, MiniAppSort) -> MiniAppDirectoryPage,
) : ViewModel() {
    private val _state = MutableStateFlow(AppSquareState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var infoJob: Job? = null
    private var infoGeneration = 0

    init { request() }

    fun search(query: String) {
        if (query == _state.value.query) return
        restart(query, _state.value.sort, debounce = true)
    }

    fun sort(sort: MiniAppSort) {
        if (sort == _state.value.sort) return
        restart(_state.value.query, sort, debounce = false)
    }

    private fun restart(query: String, sort: MiniAppSort, debounce: Boolean) {
        generation++; job?.cancel()
        dismissAbout()
        _state.value = AppSquareState(query = query, sort = sort, revision = _state.value.revision + 1)
        request(debounce = debounce)
    }

    fun showAbout(app: MiniAppEntry) {
        infoJob?.cancel()
        val token = ++infoGeneration
        _state.update { it.copy(about = AppSquareAboutState(app)) }
        infoJob = viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { loadInfo(app.id) }
                require(info.id == app.id)
                if (token == infoGeneration) _state.update { it.copy(about = AppSquareAboutState(app, info, loading = false)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == infoGeneration) _state.update { it.copy(about = AppSquareAboutState(app, loading = false, error = failure)) }
            }
        }
    }

    fun retryAbout() { _state.value.about?.let { showAbout(it.app) } }

    fun dismissAbout() {
        infoGeneration++; infoJob?.cancel(); infoJob = null
        _state.update { it.copy(about = null) }
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
                val result = withContext(Dispatchers.IO) { load(old.query.trim(), page, old.sort) }
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
