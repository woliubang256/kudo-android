package com.kudo.android.ui.topic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class ReplyCursor(val after: Int = 1, val complete: Boolean = false)
data class ReplyLoad(val cursors: Map<Int, ReplyCursor> = emptyMap(), val loading: Boolean = false, val error: Throwable? = null)
private data class JumpPage(val ids: List<Int>, val posts: List<Post> = emptyList(), val error: Exception? = null)

private fun TopicDetail.mergeSolutions(
    posts: Iterable<Post> = emptyList(), summaries: Iterable<TopicSolution> = emptyList(),
): TopicDetail {
    val merged = (solutions + summaries + posts.filter(Post::isSolution).map { post ->
        TopicSolution(post.number, post.author.username, post.author.name)
    }).distinctBy(TopicSolution::postNumber).sortedBy(TopicSolution::postNumber)
    val solutionNumbers = merged.map(TopicSolution::postNumber).toSet()
    return copy(solutions = merged, posts = this.posts.map { post ->
        if (post.isSolution || post.number in solutionNumbers) post.copy(isSolution = true) else post
    })
}

data class TopicState(
    val detail: TopicDetail? = null, val loading: Boolean = true, val loadingMore: Boolean = false,
    val error: Throwable? = null, val moreError: Throwable? = null, val busy: Set<Int> = emptySet(),
    val viewerId: Int? = null, val authorProfile: Member? = null, val authorLoading: Boolean = false,
    val authorError: Throwable? = null, val followBusy: Boolean = false,
    val originalLoading: Boolean = false, val originalError: Throwable? = null,
    val targetNumber: Int? = null,
    val related: List<Post> = emptyList(), val contextLoading: Boolean = false, val contextError: Throwable? = null,
    val replyLoads: Map<Int, ReplyLoad> = emptyMap(),
    val readNumbers: Set<Int> = emptySet(),
    val boostEmojis: Set<String>? = null, val boostRulesLoading: Boolean = false, val boostRulesError: String? = null,
    val jumping: Boolean = false, val jumpError: String? = null, val targetRevision: Long = 0,
    val loadingPrevious: Boolean = false, val previousError: Throwable? = null,
    val replyingUsers: List<Member> = emptyList(),
) {
    fun pageIds(previous: Boolean): List<Int> {
        val detail = detail ?: return emptyList()
        val edge = if (previous) detail.posts.minByOrNull(Post::number) else detail.posts.maxByOrNull(Post::number)
        val index = detail.stream.indexOf(edge?.id)
        val loaded = detail.posts.map(Post::id).toSet()
        val candidates = if (previous) detail.stream.take(index.coerceAtLeast(0))
            else detail.stream.drop(index + 1)
        val missing = candidates.filterNot { it in loaded }
        return if (previous) missing.takeLast(20) else missing.take(20)
    }
    val allPosts get() = (detail?.posts.orEmpty() + related + listOfNotNull(detail?.originalPost)).distinctBy(Post::id)
    val threads get() = postThreads(detail?.posts.orEmpty() + related)
    fun hasMoreReplies(root: Post): Boolean {
        val thread = threads.firstOrNull { it.root.id == root.id }
        val cursors = replyLoads[root.id]?.cursors.orEmpty()
        return (listOf(root) + thread?.replies.orEmpty()).any { it.replyCount > 0 && cursors[it.id]?.complete != true }
    }
}

class TopicViewModel(
    private val repository: CommunityRepository, val topicId: Int, private val number: Int? = null,
    viewerId: Int? = null,
    private val updates: TopicUpdates? = null, private val updateUser: Member? = null,
    private val updateIntervalMillis: Long = 3000,
) : ViewModel() {
    private val _state = MutableStateFlow(TopicState(viewerId = viewerId, targetNumber = number))
    val state = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var moreJob: Job? = null
    private var previousJob: Job? = null
    private var authorJob: Job? = null
    private var originalJob: Job? = null
    private var contextJob: Job? = null
    private var jumpJob: Job? = null
    private var jumpGeneration = 0L
    private val replyJobs = mutableMapOf<Int, Job>()
    private var generation = 0
    private var initialLoadCompleted = false
    private var updatesActive = false
    private var updatesJob: Job? = null
    private var updatesGeneration = 0L
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    init { refresh() }

    fun setUpdatesActive(active: Boolean) {
        if (!active) _state.update { it.copy(replyingUsers = emptyList()) }
        if (updatesActive == active) return
        updatesActive = active
        val subscription = ++updatesGeneration
        updatesJob?.cancel()
        val source = updates ?: return
        if (!active) return
        updatesJob = viewModelScope.launch {
            coroutineScope {
                val signals = Channel<Unit>(Channel.CONFLATED)
                launch {
                    try {
                        source.observeActivity(updateUser, topicId).collect { activity ->
                            if (subscription != updatesGeneration) return@collect
                            activity.replyingUsers?.let { users ->
                                _state.update { state -> state.copy(replyingUsers = users
                                    .filter { it.id > 0 && it.id != state.viewerId }.distinctBy(Member::id)) }
                            }
                            if (activity.checkReplies) signals.trySend(Unit)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        // The push origin cannot diagnose the main site's login or clearance.
                    } finally {
                        if (subscription == updatesGeneration) {
                            updatesActive = false
                            _state.update { it.copy(replyingUsers = emptyList()) }
                        }
                        signals.close()
                    }
                }
                signals.trySend(Unit) // Catch replies missed while this screen was paused.
                try {
                    for (signal in signals) {
                        delay(updateIntervalMillis)
                        signals.tryReceive()
                        state.first { !it.loading && !it.jumping && !it.loadingMore && !it.loadingPrevious &&
                            it.busy.isEmpty() && !it.followBusy && it.detail != null }
                        try {
                            if (!syncNewReplies()) signals.trySend(Unit)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            val retry = (e as? ApiFailure)?.retryAfter?.toLong()?.times(1000) ?: updateIntervalMillis * 5
                            delay(retry.coerceAtLeast(updateIntervalMillis))
                            signals.trySend(Unit)
                        }
                    }
                } finally { signals.cancel() }
            }
        }
    }

    private suspend fun syncNewReplies(): Boolean {
        val snapshot = state.value
        val detail = snapshot.detail ?: return true
        val token = generation
        val navigationToken = jumpGeneration
        val fresh = withContext(Dispatchers.IO) { repository.detail(topicId) }
        check(fresh.id == topicId) { "实时回复话题不匹配" }
        if (token != generation || navigationToken != jumpGeneration) return false
        // Keep locally excluded inaccessible floors; only extend the existing directory's tail.
        val existing = detail.stream.toSet()
        val newIds = fresh.stream.drop(fresh.stream.indexOfLast { it in existing } + 1).filterNot { it in existing }
        val requested = if (snapshot.pageIds(previous = false).isEmpty()) newIds.take(20) else emptyList()
        val received = if (requested.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            repository.posts(topicId, requested).filter { it.id in requested }
        }
        if (token != generation || navigationToken != jumpGeneration) return false
        var appended = false
        _state.update { current ->
            val latest = current.detail ?: return@update current
            val known = current.allPosts.associateBy(Post::id)
            val canAppend = current.pageIds(previous = false).isEmpty()
            val incoming = if (canAppend) received.map { known[it.id] ?: it } else emptyList()
            val missing = if (canAppend) requested.toSet() - received.map(Post::id).toSet() else emptySet()
            val posts = (latest.posts + incoming).distinctBy(Post::id).sortedBy(Post::number)
            appended = posts.size > latest.posts.size
            current.copy(detail = latest.copy(posts = posts,
                stream = (latest.stream + newIds).distinct().filterNot { it in missing },
                highestPostNumber = maxOf(latest.highestPostNumber, fresh.highestPostNumber))
                .mergeSolutions(received, fresh.solutions))
        }
        if (appended) resolveContext()
        return true
    }

    fun markRead(numbers: Set<Int>) {
        if (_state.value.viewerId == null) return
        _state.update { current ->
            val known = current.allPosts.map(Post::number).toSet()
            current.copy(readNumbers = current.readNumbers + numbers.intersect(known))
        }
    }

    fun refresh() {
        if (_state.value.busy.isNotEmpty() || _state.value.followBusy) return
        refreshJob?.cancel()
        moreJob?.cancel()
        previousJob?.cancel()
        authorJob?.cancel()
        originalJob?.cancel()
        contextJob?.cancel()
        jumpJob?.cancel(); jumpGeneration++
        replyJobs.values.forEach { it.cancel() }; replyJobs.clear()
        generation++
        _state.update { it.copy(loading = true, loadingMore = false, error = null, moreError = null,
            authorLoading = false, originalLoading = false, contextLoading = false, contextError = null, replyLoads = emptyMap(),
            jumping = false, jumpError = null, loadingPrevious = false, previousError = null) }
        val target = _state.value.targetNumber
        val trackVisit = !initialLoadCompleted && _state.value.viewerId != null
        val restoreReading = !initialLoadCompleted && target == null && _state.value.viewerId != null
        refreshJob = viewModelScope.launch {
            try {
                val first = withContext(Dispatchers.IO) { repository.detail(topicId, target, trackVisit = trackVisit) }
                var detail = first
                var resolvedTarget = target
                var adjacent = emptyList<JumpPage>()
                val resume = first.resumePostNumber?.takeIf { restoreReading && it > 1 }
                if (resume != null) {
                    try {
                        val remote = first.posts.none { it.number == resume }
                        val window = if (remote) withContext(Dispatchers.IO) { repository.detail(topicId, resume) } else first
                        check(window.id == topicId) { "续读话题不匹配" }
                        // Deleted floors can make the server return a nearby accessible window.
                        val accessible = window.posts.filter { it.number > 0 }
                            .minByOrNull { kotlin.math.abs(it.number.toLong() - resume) }
                            ?: error("续读楼层暂不可访问")
                        if (remote) {
                            val continuous = window.copy(stream = window.stream.ifEmpty { first.stream })
                            adjacent = adjacentPages(continuous)
                            val unavailable = unavailableIds(adjacent)
                            detail = continuous.copy(
                                posts = (window.posts + adjacent.flatMap { it.posts }).distinctBy(Post::id).sortedBy(Post::number),
                                stream = continuous.stream.filterNot { it in unavailable },
                                pinnedOriginal = first.originalPost,
                                highestPostNumber = maxOf(first.highestPostNumber, window.highestPostNumber))
                        }
                        resolvedTarget = accessible.number
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val reason = (e as? ApiFailure)?.takeIf { it.kind in setOf(FailureKind.VERIFICATION, FailureKind.SIGN_IN) }?.message
                        messages.trySend(reason ?: "续读位置加载失败，已从头显示")
                    }
                }
                initialLoadCompleted = true
                _state.update { it.copy(detail = detail, targetNumber = resolvedTarget, related = emptyList(), loading = false,
                    authorProfile = null, authorError = null, originalError = null,
                    previousError = adjacent.getOrNull(0)?.error, moreError = adjacent.getOrNull(1)?.error) }
                loadAuthor()
                loadOriginal()
                resolveContext()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update {
                if ((e as? ApiFailure)?.kind in setOf(FailureKind.NOT_FOUND, FailureKind.FORBIDDEN))
                    TopicState(loading = false, error = e, viewerId = it.viewerId, targetNumber = it.targetNumber)
                else it.copy(error = e, loading = false)
            } }
        }
    }

    fun applyEdit(result: PostEditResult) {
        if (result.topicId != topicId) return
        val post = result.post
        _state.update { current -> current.copy(
            related = current.related.map { if (it.id == post.id) post else it },
            detail = current.detail?.let { detail -> detail.copy(
                title = if (post.number == 1) result.title else detail.title,
                posts = detail.posts.map { if (it.id == post.id) post else it },
                pinnedOriginal = if (detail.pinnedOriginal?.id == post.id) post else detail.pinnedOriginal,
            ) },
        ) }
    }

    fun more() = loadPage(previous = false)
    fun previous() = loadPage(previous = true)

    private fun loadPage(previous: Boolean) {
        val snapshot = _state.value
        if (snapshot.detail == null || snapshot.loadingMore || snapshot.loadingPrevious || snapshot.loading || snapshot.jumping) return
        val ids = snapshot.pageIds(previous)
        if (ids.isEmpty()) return
        val requestGeneration = generation
        val navigationToken = jumpGeneration
        _state.update { if (previous) it.copy(loadingPrevious = true, previousError = null) else it.copy(loadingMore = true, moreError = null) }
        val job = viewModelScope.launch {
            try {
                val posts = withContext(Dispatchers.IO) { repository.posts(topicId, ids) }
                val available = posts.map { it.id }.toSet()
                _state.update { current ->
                    val latest = current.detail
                    if (requestGeneration != generation || navigationToken != jumpGeneration || latest == null) current else {
                        val merged = latest.copy(posts = (latest.posts + posts.map { incoming ->
                            latest.pinnedOriginal?.takeIf { it.id == incoming.id }
                                ?: current.related.firstOrNull { it.id == incoming.id } ?: incoming
                        }).distinctBy(Post::id).sortedBy(Post::number),
                            stream = latest.stream.filter { id -> id !in ids || id in available })
                            .mergeSolutions(posts)
                        current.copy(detail = merged, loadingMore = false, loadingPrevious = false)
                    }
                }
                if (requestGeneration == generation && navigationToken == jumpGeneration) resolveContext()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update {
                if (requestGeneration != generation || navigationToken != jumpGeneration) it
                else if (previous) it.copy(loadingPrevious = false, previousError = e)
                else it.copy(loadingMore = false, moreError = e)
            } }
        }
        if (previous) previousJob = job else moreJob = job
    }

    fun jumpTo(number: Int) {
        val snapshot = _state.value
        val detail = snapshot.detail ?: return
        if (snapshot.loading) return
        if (number !in 1..detail.highestPostNumber) {
            _state.update { it.copy(jumpError = "请输入 1 至 ${detail.highestPostNumber} 的楼层") }; return
        }
        jumpJob?.cancel()
        moreJob?.cancel(); previousJob?.cancel()
        val token = ++jumpGeneration
        _state.update { it.copy(jumping = true, jumpError = null, loadingMore = false, loadingPrevious = false) }
        jumpJob = viewModelScope.launch {
            try {
                val known = _state.value.detail?.posts?.firstOrNull { it.number == number }
                val window = if (known == null) withContext(Dispatchers.IO) { repository.detail(topicId, number) } else null
                if (window != null && (window.id != topicId || window.posts.none { it.number == number })) {
                    throw ApiFailure(FailureKind.SERVER, "此楼层不存在或暂不可访问")
                }
                val adjacent = if (window == null) emptyList() else adjacentPages(window.copy(stream = window.stream.ifEmpty { detail.stream }))
                if (token != jumpGeneration) return@launch
                _state.update { current ->
                    val latest = current.detail ?: return@update current
                    val existing = current.allPosts.associateBy(Post::id)
                    val unavailable = unavailableIds(adjacent)
                    val merged = if (window == null) latest else latest.copy(
                        posts = (window.posts + adjacent.flatMap { it.posts }).map { existing[it.id] ?: it }
                            .distinctBy(Post::id).sortedBy(Post::number),
                        pinnedOriginal = latest.originalPost,
                        stream = window.stream.ifEmpty { latest.stream }.filterNot { it in unavailable },
                        highestPostNumber = maxOf(latest.highestPostNumber, window.highestPostNumber))
                        .mergeSolutions(window.posts, window.solutions)
                    current.copy(detail = merged,
                        related = if (window == null) current.related else (current.related + latest.posts).distinctBy(Post::id),
                        jumping = false, targetNumber = number, targetRevision = current.targetRevision + 1,
                        previousError = adjacent.getOrNull(0)?.error, moreError = adjacent.getOrNull(1)?.error)
                }
                resolveContext()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == jumpGeneration) _state.update { it.copy(jumping = false, jumpError = e.message ?: "跳转失败，请重试") } }
        }
    }

    private suspend fun adjacentPages(window: TopicDetail): List<JumpPage> = coroutineScope {
        val target = TopicState(detail = window)
        listOf(true, false).map { previous -> async(Dispatchers.IO) {
            val ids = target.pageIds(previous)
            try {
                JumpPage(ids, if (ids.isEmpty()) emptyList() else repository.posts(topicId, ids).filter { it.id in ids })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { JumpPage(ids, error = e) }
        } }.awaitAll()
    }

    private fun unavailableIds(pages: List<JumpPage>): Set<Int> = pages.filter { it.error == null }.flatMap { page ->
        page.ids.filterNot { id -> page.posts.any { it.id == id } }
    }.toSet()

    fun resolveContext() {
        contextJob?.cancel()
        val initial = _state.value.allPosts
        if (missingReplyParents(initial).isEmpty()) {
            _state.update { it.copy(contextLoading = false, contextError = null) }; return
        }
        val token = generation
        _state.update { it.copy(contextLoading = true, contextError = null) }
        contextJob = viewModelScope.launch {
            val found = mutableListOf<Post>()
            var failure: Throwable? = null
            try {
                withContext(Dispatchers.IO) {
                    val known = initial.associateBy(Post::number).toMutableMap()
                    val failed = mutableSetOf<Int>()
                    var requests = 0
                    while (true) {
                        val missing = known.values.mapNotNull { post -> post.replyTo?.takeIf { it > 1 && it < post.number && it !in known && it !in failed } }.firstOrNull() ?: break
                        if (requests++ >= 64) { failure = ApiFailure(FailureKind.SERVER, "回复关系较深，请重试继续加载"); break }
                        try { repository.postAt(topicId, missing).let { known[it.number] = it; found += it } }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failed += missing; failure = e }
                    }
                }
                _state.update { current -> if (token != generation) current else current.copy(
                    related = (current.related + found).distinctBy(Post::id), contextLoading = false,
                    contextError = failure.takeIf { missingReplyParents(current.allPosts + found).isNotEmpty() }) }
            } catch (e: CancellationException) { throw e }
        }
    }

    fun loadReplies(root: Post, preview: Boolean = false) {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.contextLoading) return
        val previous = snapshot.replyLoads[root.id] ?: ReplyLoad()
        if (previous.loading || (preview && (previous.cursors.isNotEmpty() || previous.error != null))) return
        if (!snapshot.hasMoreReplies(root)) return
        val token = generation
        _state.update { it.copy(replyLoads = it.replyLoads + (root.id to previous.copy(loading = true, error = null))) }
        replyJobs[root.id] = viewModelScope.launch {
            try {
                // Empty quote-only pages can be skipped, but never walk an unbounded thread in one action.
                repeat(if (preview) 1 else 8) {
                    val current = _state.value
                    val load = current.replyLoads[root.id] ?: return@launch
                    val thread = current.threads.firstOrNull { it.root.id == root.id }
                    val parent = (listOf(root) + thread?.replies.orEmpty()).firstOrNull { it.replyCount > 0 && load.cursors[it.id]?.complete != true } ?: return@repeat
                    val after = load.cursors[parent.id]?.after ?: 1
                    val page = withContext(Dispatchers.IO) { repository.replies(parent.id, after) }
                    val cursor = page.maxOfOrNull(Post::number) ?: after
                    val replies = page.filter { it.replyTo == parent.number && it.number > parent.number }
                    if (token != generation) return@launch
                    _state.update { latest ->
                        val known = latest.allPosts.map(Post::id).toSet()
                        val active = latest.replyLoads[root.id] ?: load
                        latest.copy(related = latest.related + replies.filterNot { it.id in known }.distinctBy(Post::id),
                            contextError = latest.contextError.takeIf { missingReplyParents(latest.allPosts + replies).isNotEmpty() },
                            replyLoads = latest.replyLoads + (root.id to active.copy(cursors = active.cursors +
                                (parent.id to ReplyCursor(cursor, page.size < 20 || cursor <= after)))))
                    }
                    if (replies.isNotEmpty()) return@launch
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) _state.update { current ->
                val load = current.replyLoads[root.id] ?: ReplyLoad()
                current.copy(replyLoads = current.replyLoads + (root.id to load.copy(loading = false, error = e)))
            } }
            finally { if (token == generation) _state.update { current ->
                val load = current.replyLoads[root.id] ?: ReplyLoad()
                current.copy(replyLoads = current.replyLoads + (root.id to load.copy(loading = false)))
            } }
        }
    }

    private fun loadOriginal() {
        val detail = _state.value.detail ?: return
        val id = detail.stream.firstOrNull() ?: return
        if (detail.originalPost != null) return
        val requestGeneration = generation
        _state.update { it.copy(originalLoading = true, originalError = null) }
        originalJob = viewModelScope.launch {
            try {
                val post = withContext(Dispatchers.IO) { repository.posts(topicId, listOf(id)) }
                    .firstOrNull { it.id == id && it.number == 1 }
                    ?: throw ApiFailure(FailureKind.SERVER, "主帖暂不可用")
                _state.update { current ->
                    if (generation != requestGeneration) current else current.copy(originalLoading = false,
                        detail = current.detail?.copy(pinnedOriginal = post, author = post.author,
                            createdAt = current.detail.createdAt.ifBlank { post.createdAt }))
                }
                if (generation == requestGeneration && detail.author == null) loadAuthor()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(originalLoading = false, originalError = e) } }
        }
    }

    private fun loadAuthor() {
        val author = _state.value.detail?.author ?: return
        if (author.id == _state.value.viewerId) {
            _state.update { it.copy(authorProfile = author, authorLoading = false, authorError = null) }
            return
        }
        authorJob?.cancel()
        val requestGeneration = generation
        _state.update { it.copy(authorLoading = true, authorError = null) }
        authorJob = viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { repository.user(author.username) }
                if (profile.id != author.id || !profile.username.equals(author.username, ignoreCase = true)) {
                    throw ApiFailure(FailureKind.SERVER, "作者状态需重新加载")
                }
                _state.update { if (generation != requestGeneration) it else it.copy(authorProfile = profile, authorLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(authorLoading = false, authorError = e) } }
        }
    }

    fun follow() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.followBusy || snapshot.authorLoading) return
        val author = snapshot.authorProfile
        if (author == null) { loadAuthor(); return }
        if (author.id == snapshot.viewerId || (!author.canFollow && !author.isFollowed)) return
        _state.update { it.copy(followBusy = true) }
        viewModelScope.launch {
            try {
                val followed = withContext(Dispatchers.IO) { repository.follow(author) }
                _state.update { it.copy(authorProfile = author.copy(isFollowed = followed)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { messages.send(e.message ?: "关注操作失败") }
            finally { _state.update { it.copy(followBusy = false) } }
        }
    }

    fun like(post: Post) = action(post, block = repository::like)
    fun react(post: Post, reaction: String) = action(post) { latest ->
        val options = _state.value.detail?.validReactions.orEmpty()
        require(reaction in options || reaction == latest.activeReaction || (!latest.reactionsAvailable && reaction == "heart")) { "此回应暂不可用" }
        repository.react(latest, reaction)
    }
    fun bookmark(post: Post) = action(post) { latest -> latest.copy(bookmarkId = repository.bookmark(latest)) }
    fun vote(post: Post, name: String, options: List<String>) {
        if (_state.value.viewerId == null || _state.value.detail?.archived != false) return
        action(post) { repository.vote(it, name, options) }
    }

    fun loadBoostRules() {
        if (_state.value.boostEmojis != null || _state.value.boostRulesLoading) return
        _state.update { it.copy(boostRulesLoading = true, boostRulesError = null) }
        viewModelScope.launch {
            try {
                val names = withContext(Dispatchers.IO) { repository.emojiUrls().keys }
                _state.update { it.copy(boostEmojis = names, boostRulesLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(boostRulesLoading = false, boostRulesError = e.message ?: "表情规则读取失败") } }
        }
    }

    fun boost(post: Post, raw: String, accepted: () -> Unit) {
        val emojis = _state.value.boostEmojis ?: return
        if (BoostRules.count(raw, emojis).problem != null) return
        action(post, accepted) { latest -> repository.boost(latest, raw, emojis) }
    }

    private fun action(post: Post, accepted: () -> Unit = {}, block: suspend (Post) -> Post) {
        val snapshot = _state.value
        if (snapshot.loading || post.id in snapshot.busy) return
        val latest = snapshot.allPosts.firstOrNull { it.id == post.id } ?: return
        _state.update { it.copy(busy = it.busy + post.id) }
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { block(latest) }
                _state.update { current -> current.copy(related = current.related.map { if (it.id == updated.id) updated else it }, detail = current.detail?.let { detail ->
                    detail.copy(posts = detail.posts.map { old -> if (old.id == updated.id) updated else old },
                        pinnedOriginal = if (detail.pinnedOriginal?.id == updated.id) updated else detail.pinnedOriginal)
                }) }
                accepted()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { messages.send(e.message ?: "操作失败") }
            finally { _state.update { it.copy(busy = it.busy - post.id) } }
        }
    }
}
