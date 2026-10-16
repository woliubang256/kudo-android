package com.kudo.android.ui.topic

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.filter

enum class TopicActionPanel { MENU, HISTORY, REPORT, IGNORE }
data class TopicActionState(
    val topicId: Int? = null, val fromDetail: Boolean = false, val post: Post? = null,
    val panel: TopicActionPanel = TopicActionPanel.MENU, val loading: Boolean = false,
    val busy: Boolean = false, val error: Throwable? = null, val revision: PostRevision? = null,
    val flags: FlagConfiguration? = null, val selectedFlag: FlagReason? = null,
    val message: String = "", val confirmed: Boolean = false, val duration: IgnoreDuration? = null,
    val writeLocked: Boolean = false,
    val isReply: Boolean = false,
    val topicTitle: String = "", val composingMessage: Boolean = false,
) {
    val link: String? get() = topicId?.let { id ->
        if (isReply) post?.let { "https://linux.do/t/topic/$id/${it.number}" }
        else "https://linux.do/t/topic/$id"
    }
}
sealed interface TopicActionEvent {
    data class ComposePrivateMessage(val recipient: String, val source: PostMessageSource, val contextVersion: Long) : TopicActionEvent
    data class Message(val text: String) : TopicActionEvent
    data class Ignored(val viewerId: Int, val username: String) : TopicActionEvent
    data object Refresh : TopicActionEvent
}

class TopicActionsViewModel(private val repository: CommunityRepository, val viewer: Member?) : ViewModel() {
    var state by mutableStateOf(TopicActionState()); private set
    private var read: Job? = null
    private val messages = Channel<TopicActionEvent>(Channel.BUFFERED)
    private var contextVersion = 0L
    val events = messages.receiveAsFlow().filter { event -> event !is TopicActionEvent.ComposePrivateMessage || event.contextVersion == contextVersion }
    private val uncertainReports = mutableSetOf<Pair<Int, Int>>()
    private val successfulReports = mutableSetOf<Pair<Int, Int>>()
    private val uncertainIgnores = mutableSetOf<String>()

    fun open(id: Int, fromDetail: Boolean = false, initial: Post? = null, isReply: Boolean = false) {
        if (state.busy) return
        if (isReply && initial == null) return
        read?.cancel()
        contextVersion++
        state = TopicActionState(topicId = id, fromDetail = fromDetail, post = initial?.takeIf { isReply || it.number == 1 }, isReply = isReply)
        if (state.post == null) loadPost()
    }
    fun dismiss() {
        if (state.busy) return
        contextVersion++
        read?.cancel(); state = TopicActionState()
    }
    fun loadPost() {
        if (state.isReply) return
        val id = state.topicId ?: return
        load {
            val (post, title) = withContext(Dispatchers.IO) {
                val detail = repository.detail(id, 1)
                val original = detail.originalPost ?: detail.stream.firstOrNull()?.let { repository.posts(id, listOf(it)).firstOrNull { post -> post.number == 1 } }
                original to detail.title
            }
            state = state.copy(post = post ?: error("暂时无法读取主帖"), topicTitle = title)
        }
    }
    fun privateMessage() {
        val snapshot = state
        val user = viewer ?: return
        val post = snapshot.post ?: return
        val id = snapshot.topicId ?: return
        if (snapshot.busy || snapshot.loading || user.id == post.author.id || post.author.username.isBlank()) return
        val menuVersion = contextVersion
        state = state.copy(composingMessage = true)
        load {
            val source = withContext(Dispatchers.IO) {
                val profile = repository.publicProfile(post.author.username)
                check(profile.canMessage) { "暂时无法向该用户发送私信" }
                val title = snapshot.topicTitle.ifBlank { repository.detail(id, 1).title }
                check(title.isNotBlank()) { "暂时无法读取话题标题" }
                PostMessageSource(id, post.number, title)
            }
            messages.send(TopicActionEvent.ComposePrivateMessage(post.author.username, source, menuVersion))
            state = TopicActionState()
        }
    }
    fun history(number: Int? = null) {
        val post = state.post?.takeIf { it.hasEditHistory } ?: return
        state = state.copy(panel = TopicActionPanel.HISTORY)
        load { state = state.copy(revision = withContext(Dispatchers.IO) { repository.revision(post, number) }) }
    }
    fun report() {
        val post = state.post ?: return
        state = state.copy(panel = TopicActionPanel.REPORT)
        load {
            val config = withContext(Dispatchers.IO) { repository.flagConfiguration() }
            state = state.copy(flags = config.copy(reasons = config.available(post).filterNot { (post.id to it.id) in successfulReports }))
        }
    }
    fun chooseFlag(reason: FlagReason) {
        if (state.busy || reason !in state.flags?.reasons.orEmpty()) return
        state = state.copy(selectedFlag = reason, confirmed = false, error = null,
            writeLocked = (state.post!!.id to reason.id) in uncertainReports)
    }
    fun message(value: String) { if (!state.busy) state = state.copy(message = value) }
    fun confirm(value: Boolean) { if (!state.busy) state = state.copy(confirmed = value) }
    fun ignore() {
        val author = state.post?.author ?: return
        state = state.copy(panel = TopicActionPanel.IGNORE, error = null, writeLocked = author.username in uncertainIgnores)
    }
    fun duration(value: IgnoreDuration) { if (!state.busy) state = state.copy(duration = value) }
    fun submitReport() {
        val snapshot = state
        val post = snapshot.post ?: return
        val config = snapshot.flags ?: return
        val reason = snapshot.selectedFlag ?: return
        if (snapshot.busy || snapshot.writeLocked || !config.valid(reason, snapshot.message, snapshot.confirmed)) return
        write(onUncertain = { uncertainReports += post.id to reason.id }) {
            withContext(Dispatchers.IO) { repository.flag(post, config, reason, snapshot.message, snapshot.confirmed) }
            successfulReports += post.id to reason.id
            messages.send(TopicActionEvent.Refresh)
            messages.send(TopicActionEvent.Message(if (reason.contactAuthor) "消息已发送给作者" else "举报已提交，感谢维护社区"))
        }
    }
    fun submitIgnore() {
        val post = state.post ?: return
        val user = viewer ?: return
        val duration = state.duration ?: return
        if (state.busy || state.writeLocked) return
        write(onUncertain = { uncertainIgnores += post.author.username }) {
            withContext(Dispatchers.IO) { repository.ignoreUser(user, post.author.username, duration.expiresAt()) }
            messages.send(TopicActionEvent.Ignored(user.id, post.author.username))
            messages.send(TopicActionEvent.Message("已忽略 @${post.author.username}，可在设置恢复"))
        }
    }
    fun bookmark() {
        val post = state.post ?: return
        if (state.busy || state.writeLocked) return
        write {
            withContext(Dispatchers.IO) { repository.bookmark(post) }
            messages.send(TopicActionEvent.Refresh)
            messages.send(TopicActionEvent.Message(if (post.bookmarkId == null) "已收藏" else "已取消收藏"))
        }
    }
    private fun load(block: suspend () -> Unit) {
        if (state.busy) return
        read?.cancel()
        state = state.copy(loading = true, error = null)
        read = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state = state.copy(error = e) }
            finally { if (isActive) state = state.copy(loading = false) }
        }
    }
    private fun write(onUncertain: () -> Unit = {}, block: suspend () -> Unit) {
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block(); state = TopicActionState() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val uncertain = (e as? ApiFailure)?.kind == FailureKind.NETWORK || e.message.orEmpty().contains("未确认")
                if (uncertain) onUncertain()
                state = state.copy(busy = false, error = e, writeLocked = uncertain)
            }
        }
    }
}
