package com.kudo.android.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CommunityDirectoryState(
    val accountId: Int? = null, val directory: CommunityDirectory? = null,
    val loading: Boolean = false, val error: Throwable? = null,
    val tagGroups: List<DirectoryTagGroup>? = null, val tagsLoading: Boolean = false, val tagsError: Throwable? = null,
) {
    fun forAccount(id: Int?) = if (accountId == id) this else CommunityDirectoryState(accountId = id)
}

class CommunityDirectoryViewModel(
    private val fetchDirectory: suspend (Int?) -> CommunityDirectory,
    private val fetchTags: suspend () -> List<DirectoryTagGroup>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    constructor(repository: CommunityRepository) : this(
        { id -> withContext(Dispatchers.IO) { repository.communityDirectory(id) } },
        { withContext(Dispatchers.IO) { repository.directoryTagGroups() } },
    )
    private val _state = MutableStateFlow(CommunityDirectoryState())
    val state = _state.asStateFlow()
    private var generation = 0
    private var directoryJob: Job? = null
    private var tagsJob: Job? = null
    private var loadedAt: Long? = null

    fun useAccount(id: Int?) {
        if (id == _state.value.accountId) return
        generation++
        directoryJob?.cancel(); tagsJob?.cancel()
        loadedAt = null
        _state.value = CommunityDirectoryState(accountId = id)
    }

    fun loadIfStale() {
        if (loadedAt == null || clock() - loadedAt!! >= 60_000) load(refresh = true)
    }

    fun load(refresh: Boolean = false) {
        val current = _state.value
        if (current.loading || (!refresh && current.directory != null)) return
        val token = generation
        _state.update { it.copy(loading = true, error = null) }
        directoryJob = viewModelScope.launch {
            try {
                val result = fetchDirectory(current.accountId)
                if (result.accountId != current.accountId) throw ApiFailure(FailureKind.SIGN_IN, "登录状态已变化，请刷新后重试")
                if (token == generation) {
                    loadedAt = clock()
                    _state.update { it.copy(directory = result, error = null) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) _state.update { it.copy(error = e) } }
            finally { if (token == generation) _state.update { it.copy(loading = false) } }
        }
    }

    fun loadTags(refresh: Boolean = false) {
        val current = _state.value
        if (current.tagsLoading || (!refresh && current.tagGroups != null)) return
        val token = generation
        _state.update { it.copy(tagsLoading = true, tagsError = null) }
        tagsJob = viewModelScope.launch {
            try {
                val result = fetchTags()
                if (token == generation) _state.update { it.copy(tagGroups = result, tagsError = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) _state.update { it.copy(tagsError = e) } }
            finally { if (token == generation) _state.update { it.copy(tagsLoading = false) } }
        }
    }
}
