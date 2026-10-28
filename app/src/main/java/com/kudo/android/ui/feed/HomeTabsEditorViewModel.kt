package com.kudo.android.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class HomeTabsEditorState(
    val accountId: Int?, val tabs: List<HomeTab>, val savedTabs: List<HomeTab>,
    val source: HomeTabType = HomeTabType.FILTER, val query: String = "",
    val categories: List<Category> = emptyList(), val tags: List<TopicTag> = emptyList(),
    val categoriesLoading: Boolean = false, val tagsLoading: Boolean = false,
    val categoryError: Throwable? = null, val tagError: Throwable? = null,
) {
    val dirty: Boolean get() = tabs != savedTabs
    val loading: Boolean get() = when (source) {
        HomeTabType.CATEGORY -> categoriesLoading
        HomeTabType.TAG -> tagsLoading
        else -> false
    }
    val error: Throwable? get() = when (source) {
        HomeTabType.CATEGORY -> categoryError
        HomeTabType.TAG -> tagError
        else -> null
    }
    val candidates: List<HomeTab> get() = when (source) {
        HomeTabType.CATEGORY -> categories.filter { it.parentId == null }.map(HomeTab::category)
        HomeTabType.TAG -> tags.map(HomeTab::tag)
        else -> defaultHomeTabs(accountId != null)
    }.filter { source == HomeTabType.TAG || it.label.contains(query.trim(), true) || it.value.contains(query.trim(), true) }
}

internal class HomeTabsEditorViewModel(
    private val repository: CommunityRepository, private val accountId: Int?,
    initialTabs: List<HomeTab> = defaultHomeTabs(accountId != null),
    private val persist: (List<HomeTab>) -> Unit = {},
) : ViewModel() {
    private val initial = normalizeHomeTabs(initialTabs, accountId != null)
    private val _state = MutableStateFlow(HomeTabsEditorState(accountId, initial, initial))
    val state = _state.asStateFlow()
    private var categoryJob: Job? = null
    private var tagJob: Job? = null
    private var tagGeneration = 0L

    init { loadCategories() }

    fun move(key: String, index: Int) {
        _state.update { current ->
            val previous = current.tabs.indexOfFirst { it.key == key }
            if (previous < 0) current else {
                val tabs = current.tabs.toMutableList()
                val moved = tabs.removeAt(previous)
                tabs.add(index.coerceIn(0, tabs.size), moved)
                current.copy(tabs = tabs)
            }
        }
    }

    fun add(tab: HomeTab) {
        _state.update { current ->
            if (current.tabs.any { it.key == tab.key }) current
            else current.copy(tabs = normalizeHomeTabs(current.tabs + tab, accountId != null))
        }
    }

    fun remove(key: String) {
        _state.update { current ->
            if (current.tabs.size <= 1) current else current.copy(tabs = current.tabs.filterNot { it.key == key })
        }
    }

    fun restore() { _state.update { it.copy(tabs = defaultHomeTabs(accountId != null)) } }
    fun save() { persist(_state.value.tabs); _state.update { it.copy(savedTabs = it.tabs) } }

    fun source(value: HomeTabType) {
        if (value == HomeTabType.DIRECTORY || value == _state.value.source) return
        _state.update { it.copy(source = value) }
        suggestTags()
    }

    fun query(value: String) {
        _state.update { it.copy(query = value) }
        suggestTags()
    }

    fun retry() {
        when (_state.value.source) {
            HomeTabType.CATEGORY -> loadCategories()
            HomeTabType.TAG -> suggestTags()
            else -> Unit
        }
    }

    private fun loadCategories() {
        categoryJob?.cancel()
        _state.update { it.copy(categoriesLoading = true, categoryError = null) }
        categoryJob = viewModelScope.launch {
            try {
                val categories = withContext(Dispatchers.IO) { repository.categoryCatalog.value.ifEmpty { repository.categories() } }
                _state.update { it.copy(categories = categories, categoriesLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(categoriesLoading = false, categoryError = error) } }
        }
    }

    private fun suggestTags() {
        tagJob?.cancel()
        val generation = ++tagGeneration
        val current = _state.value
        val term = current.query.trim()
        _state.update { it.copy(tags = emptyList(), tagsLoading = current.source == HomeTabType.TAG && term.isNotBlank(), tagError = null) }
        if (current.source != HomeTabType.TAG || term.isBlank()) return
        tagJob = viewModelScope.launch {
            delay(300)
            try {
                val tags = withContext(Dispatchers.IO) { repository.searchTags(term) }
                if (generation == tagGeneration) _state.update { it.copy(tags = tags, tagsLoading = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == tagGeneration) _state.update { it.copy(tagsLoading = false, tagError = error) } }
        }
    }
}
