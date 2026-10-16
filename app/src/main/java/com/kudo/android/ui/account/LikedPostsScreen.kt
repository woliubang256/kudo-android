package com.kudo.android.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import com.kudo.android.ui.components.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

internal class LikedPostsViewModel(private val repository: CommunityRepository, private val username: String) : ViewModel() {
    private val mutable = MutableStateFlow(ProfileFeedState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var generation = 0
    private var failedMore = false

    init { load() }

    fun retry() = load(failedMore)

    fun load(more: Boolean = false) {
        val current = mutable.value
        if (more && (current.loading || current.next == null)) return
        request?.cancel()
        val epoch = ++generation
        val cursor = if (more) current.next else null
        failedMore = more
        mutable.update { it.copy(loading = true, error = null) }
        request = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { repository.profilePage(username, ProfileTab.LIKES, cursor) }
                if (epoch == generation) mutable.value = ProfileFeedState(
                    entries = ((if (more) current.entries else emptyList()) + page.entries).distinctBy(ProfileEntry::key),
                    next = page.next?.takeUnless { it == cursor }, loaded = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (epoch == generation) mutable.update { it.copy(loading = false, error = e) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LikedPostsScreen(state: ProfileFeedState, back: () -> Unit, login: () -> Unit,
    open: (String) -> Unit, refresh: () -> Unit, more: () -> Unit, retry: () -> Unit) {
    val scroll = rememberLazyListState()
    val pull = rememberPullToRefreshState()
    val refreshing = state.loading && state.entries.isNotEmpty()
    LaunchedEffect(state.entries.size, state.loading, state.next, state.error) {
        snapshotFlow { scroll.isScrollInProgress to (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) }
            .distinctUntilChanged().collect { (scrolling, last) ->
                if (scrolling && state.next != null && !state.loading && state.error == null &&
                    state.entries.isNotEmpty() && last >= scroll.layoutInfo.totalItemsCount - 3) more()
            }
    }
    MaterialPageScaffold(modifier = Modifier.testTag("liked-posts"), header = {
        AccountPageTopBar("我的点赞", back) {
            CommunityIconButton(Icons.Rounded.Refresh, "刷新", refresh)
        }
    }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, state = pull,
            modifier = Modifier.fillMaxSize(), indicator = {
                PullToRefreshDefaults.IndicatorBox(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                    containerColor = MaterialTheme.colorScheme.surface) { CommunityLoadingIndicator(Modifier.size(16.dp), rotating = refreshing) }
            }) {
            LazyColumn(Modifier.fillMaxSize().testTag("liked-posts-list").quickScrollTarget(scroll), state = scroll,
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(state.entries, key = ProfileEntry::key) { ProfileEntryRow(it, open) }
                if (state.loading) item { LoadingRow() }
                state.error?.let { item { ErrorState(it, retry, login) } }
                if (state.loaded && !state.loading && state.error == null && state.entries.isEmpty()) item {
                    EmptyState("暂无点赞", "你送出的赞会出现在这里。", Icons.Rounded.ThumbUp)
                }
                if (state.loaded && !state.loading && state.error == null && state.entries.isNotEmpty() && state.next == null) item {
                    Text("已显示全部", Modifier.padding(18.dp))
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}
