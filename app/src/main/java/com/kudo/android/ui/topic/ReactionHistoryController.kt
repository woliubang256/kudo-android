package com.kudo.android.ui.topic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kudo.android.data.Post
import com.kudo.android.data.ReactionHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class ReactionHistoryState(
    val post: Post? = null, val history: ReactionHistory? = null, val reaction: String? = null,
    val loading: Boolean = false, val error: Throwable? = null, val visibleCount: Int = 40,
)

internal class ReactionHistoryController(private val scope: CoroutineScope, private val load: suspend (Post, Int) -> ReactionHistory) {
    var state by mutableStateOf(ReactionHistoryState())
        private set
    private var request: Job? = null
    private var generation = 0

    fun open(post: Post) {
        dismiss()
        state = ReactionHistoryState(post = post)
        retry()
    }

    fun dismiss() {
        generation++
        request?.cancel()
        request = null
        state = ReactionHistoryState()
    }

    fun select(reaction: String?) {
        if (reaction == null || state.history?.groups?.any { it.reaction == reaction } == true) {
            state = state.copy(reaction = reaction, visibleCount = 40)
        }
    }

    fun more() {
        val history = state.history ?: return
        if (state.visibleCount < history.users(state.reaction).size) state = state.copy(visibleCount = state.visibleCount + 40)
        else if (history.nextPage != null) fetch(history.nextPage)
    }

    fun retry() = fetch(state.history?.nextPage ?: 0)

    private fun fetch(page: Int) {
        val post = state.post ?: return
        if (state.loading) return
        val token = generation
        state = state.copy(loading = true, error = null)
        request = scope.launch {
            try {
                val result = load(post, page)
                currentCoroutineContext().ensureActive()
                if (token == generation) state = state.copy(loading = false,
                    history = if (page == 0) result else state.history?.append(result) ?: result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation) state = state.copy(loading = false, error = failure)
            }
        }
    }
}
