package com.kudo.android.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SearchState(
    val accountId: Int? = null, val query: String = "", val kind: SearchKind = SearchKind.POSTS,
    val sort: SearchSort = SearchSort.RELEVANCE, val filters: SearchFilters = SearchFilters(),
    val hits: SearchResults = SearchResults(), val loading: Boolean = false, val searched: Boolean = false,
    val hasMore: Boolean = false, val page: Int = 1, val error: Throwable? = null, val moreError: Throwable? = null,
    val recent: List<String> = emptyList(), val historyLoading: Boolean = false, val historyError: Throwable? = null,
    val historyClearing: Boolean = false, val historyClearError: Throwable? = null,
    val categories: List<Category> = emptyList(), val capabilities: SearchCapabilities = SearchCapabilities(),
    val metadataLoading: Boolean = false, val metadataLoaded: Boolean = false, val metadataError: Throwable? = null,
    val tagSuggestions: List<TopicTag> = emptyList(), val tagsLoading: Boolean = false, val tagsError: Throwable? = null,
) {
    val results: List<Topic> get() = hits.posts.map { it.topic }
    fun forAccount(id: Int?) = if (id == accountId) this else SearchState(accountId = id)
}

class SearchViewModel(
    private val repository: CommunityRepository, initialAccountId: Int? = null, private val initialQuery: String = "",
) : ViewModel() {
    private val _state = MutableStateFlow(SearchState(accountId = initialAccountId))
    val state = _state.asStateFlow()
    private var searchJob: Job? = null
    private var historyJob: Job? = null
    private var metadataJob: Job? = null
    private var tagsJob: Job? = null
    private var generation = 0
    private var searchGeneration = 0
    private var historyGeneration = 0
    private var tagGeneration = 0
    var scrollIndex: Int = 0
        private set
    var scrollOffset: Int = 0
        private set

    fun saveScrollPosition(index: Int, offset: Int) {
        scrollIndex = index.coerceAtLeast(0)
        scrollOffset = offset.coerceAtLeast(0)
    }

    private fun resetScrollPosition() {
        scrollIndex = 0
        scrollOffset = 0
    }

    init { refreshHistory(); if (initialQuery.isNotBlank()) search(initialQuery) }

    fun useAccount(id: Int?) {
        if (id == _state.value.accountId) return
        generation++; searchGeneration++; historyGeneration++; tagGeneration++
        searchJob?.cancel(); historyJob?.cancel(); metadataJob?.cancel(); tagsJob?.cancel()
        resetScrollPosition()
        _state.value = SearchState(accountId = id)
        refreshHistory()
        if (initialQuery.isNotBlank()) search(initialQuery)
    }

    fun refreshHistory() {
        val id = _state.value.accountId ?: return
        if (_state.value.historyClearing) return
        historyJob?.cancel()
        val token = generation
        val historyToken = ++historyGeneration
        _state.update { it.copy(historyLoading = true, historyError = null) }
        historyJob = viewModelScope.launch {
            try {
                val recent = withContext(Dispatchers.IO) { repository.recentSearches(id) }
                if (token == generation && historyToken == historyGeneration) _state.update { it.copy(recent = recent, historyLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation && historyToken == historyGeneration) _state.update { it.copy(historyLoading = false, historyError = e) } }
        }
    }

    fun clearHistory() {
        val id = _state.value.accountId ?: return
        if (_state.value.historyClearing) return
        historyJob?.cancel()
        val token = generation
        val historyToken = ++historyGeneration
        _state.update { it.copy(historyLoading = false, historyClearing = true, historyClearError = null) }
        historyJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.clearRecentSearches(id) }
                if (token == generation && historyToken == historyGeneration) _state.update {
                    it.copy(recent = emptyList(), historyClearing = false, historyError = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation && historyToken == historyGeneration) _state.update {
                    it.copy(historyClearing = false, historyClearError = e)
                }
            }
        }
    }

    fun clear() {
        searchGeneration++; searchJob?.cancel()
        resetScrollPosition()
        _state.update { it.copy(query = "", hits = SearchResults(), filters = SearchFilters(), loading = false,
            searched = false, hasMore = false, page = 1, error = null, moreError = null) }
        refreshHistory()
    }

    fun changeKind(kind: SearchKind) {
        if (kind == _state.value.kind) return
        searchGeneration++; searchJob?.cancel()
        _state.update { it.copy(kind = kind, hits = SearchResults(), loading = false, hasMore = false, page = 1, error = null, moreError = null) }
        if (_state.value.searched) search(_state.value.query)
    }

    fun changeSort(sort: SearchSort) {
        if (sort.personal && _state.value.accountId == null) return
        _state.update { it.copy(sort = sort) }
        if (_state.value.searched) search(withSearchSort(_state.value.query, sort))
    }

    fun applyFilters(query: String, filters: SearchFilters) {
        if (filters.validationError() != null) return
        search(withSearchFilters(query, filters, _state.value.sort))
    }

    fun search(query: String, more: Boolean = false) {
        val old = _state.value
        val trimmed = if (more) old.query else query.trim()
        if (trimmed.isEmpty() || (more && (old.loading || !old.hasMore || old.kind != SearchKind.POSTS))) return
        if (!more) resetScrollPosition()
        searchJob?.cancel()
        val token = ++searchGeneration
        val page = if (more) old.page + 1 else 1
        val sort = searchSort(trimmed) ?: old.sort
        _state.update { it.copy(query = trimmed, sort = sort, filters = parseSearchFilters(trimmed),
            hits = if (more) it.hits else SearchResults(), loading = true, searched = true,
            hasMore = if (more) it.hasMore else false, page = if (more) it.page else 1, error = null, moreError = null) }
        val requestQuery = if (old.kind == SearchKind.POSTS) withSearchSort(trimmed, sort) else entitySearchTerm(trimmed)
        searchJob = viewModelScope.launch {
            try {
                val result = if (requestQuery.isBlank()) SearchResults() else withContext(Dispatchers.IO) {
                    repository.searchResults(requestQuery, old.kind, page)
                }
                if (token == searchGeneration) {
                    _state.update { it.copy(hits = if (more) result.copy(posts = (it.hits.posts + result.posts).distinctBy(SearchHit::postId)) else result,
                        loading = false, page = page, hasMore = result.hasMore) }
                    if (!more && old.kind == SearchKind.POSTS) refreshHistory()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == searchGeneration) _state.update { if (more) it.copy(loading = false, moreError = e) else it.copy(loading = false, error = e) }
            }
        }
    }

    fun loadMetadata() {
        if (_state.value.metadataLoading || _state.value.metadataLoaded) return
        val token = generation
        val id = _state.value.accountId
        _state.update { it.copy(metadataLoading = true, metadataError = null) }
        metadataJob = viewModelScope.launch {
            try {
                val (categories, capabilities) = withContext(Dispatchers.IO) {
                    repository.categoryCatalog.value.ifEmpty { repository.categories() } to repository.searchCapabilities(id)
                }
                if (token == generation) _state.update { it.copy(categories = categories, capabilities = capabilities,
                    metadataLoading = false, metadataLoaded = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) _state.update { it.copy(metadataLoading = false, metadataError = e,
                categories = repository.categoryCatalog.value) } }
        }
    }

    fun suggestTags(query: String) {
        tagsJob?.cancel()
        val token = ++tagGeneration
        if (query.isBlank()) { _state.update { it.copy(tagSuggestions = emptyList(), tagsLoading = false, tagsError = null) }; return }
        _state.update { it.copy(tagSuggestions = emptyList(), tagsLoading = true, tagsError = null) }
        tagsJob = viewModelScope.launch {
            delay(300)
            try {
                val tags = withContext(Dispatchers.IO) { repository.searchTags(query.trim()) }
                if (token == tagGeneration) _state.update { it.copy(tagSuggestions = tags, tagsLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == tagGeneration) _state.update { it.copy(tagsLoading = false, tagsError = e) } }
        }
    }
}

private fun entitySearchTerm(query: String): String {
    val filters = parseSearchFilters(query)
    val text = withSearchFilters(query, SearchFilters(), SearchSort.RELEVANCE).removeSuffix("order:relevance").trim()
    return text.trim('"').ifBlank { filters.username }
}
