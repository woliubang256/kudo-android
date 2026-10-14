package com.kudo.android.ui.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PostHistoryState(val versions: List<HistoryVersion> = emptyList(), val loading: Boolean = true,
    val loadingMore: Boolean = false, val previous: Int? = null, val error: Throwable? = null, val moreError: Throwable? = null)

class PostHistoryViewModel(private val repository: CommunityRepository, val postId: Int) : ViewModel() {
    private val _state = MutableStateFlow(PostHistoryState())
    val state = _state.asStateFlow()
    private var post: Post? = null
    private var job: Job? = null
    private var generation = 0
    init { reload() }
    fun reload() {
        val token = ++generation
        job?.cancel(); post = null
        _state.value = PostHistoryState()
        job = viewModelScope.launch {
            try {
                val (source, first) = withContext(Dispatchers.IO) {
                    val source = repository.post(postId)
                    if (source.id != postId) throw ApiFailure(FailureKind.SERVER, "编辑历史与当前楼层不符")
                    if (!source.hasEditHistory) throw ApiFailure(FailureKind.FORBIDDEN, "此楼层没有可查看的编辑历史")
                    source to repository.historyRevision(source)
                }
                if (token != generation) return@launch
                post = source
                _state.value = PostHistoryState(listOfNotNull(first.version, first.original), loading = false, previous = earlier(first))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) _state.value = PostHistoryState(loading = false, error = e) }
        }
    }
    fun more() {
        val snapshot = state.value
        val previous = snapshot.previous ?: return
        val source = post ?: return
        if (snapshot.loading || snapshot.loadingMore) return
        val token = generation
        _state.update { it.copy(loadingMore = true, moreError = null) }
        job = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { repository.historyRevision(source, previous) }
                if (token != generation) return@launch
                _state.update { it.copy(versions = (it.versions + listOfNotNull(page.version, page.original)).distinctBy(HistoryVersion::number),
                    previous = earlier(page), loadingMore = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) _state.update { it.copy(loadingMore = false, moreError = e) } }
        }
    }
    private fun earlier(page: HistoryRevision): Int? {
        val pointer = page.revision.previous
        if (pointer != null && pointer >= page.revision.current) throw ApiFailure(FailureKind.SERVER, "历史版本顺序异常，请重试")
        return pointer
    }
}
