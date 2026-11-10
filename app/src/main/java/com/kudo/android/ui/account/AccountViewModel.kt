package com.kudo.android.ui.account

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class AccountViewModel(
    private val repository: CommunityRepository,
    private val resetSession: () -> Unit,
    private val clearSession: suspend () -> Unit,
    private val updates: NotificationUpdates? = null,
    private val delivery: NotificationDelivery = NotificationDelivery.None,
) : ViewModel() {
    constructor(app: AppContainer) : this(app.repository, app.client::resetSession, {
        app.cookies.clear()
        app.client.resetSession()
        app.settings.clearAccountData()
        android.webkit.WebStorage.getInstance().deleteAllData()
    }, DiscourseMessageBus(app.client, userAgent = app.userAgent, route = app.network::client), app.notifications)
    var user by mutableStateOf<Member?>(null); private set
    var checkingSession by mutableStateOf(false); private set
    var sessionReady by mutableStateOf(false); private set
    var unreadCount by mutableIntStateOf(0); private set
    var sessionError by mutableStateOf<Throwable?>(null); private set
    var categories by mutableStateOf<List<Category>>(emptyList()); private set
    var categoryError by mutableStateOf<Throwable?>(null); private set
    var categoriesLoading by mutableStateOf(false); private set
    var notices by mutableStateOf<List<Notice>>(emptyList()); private set
    var noticesError by mutableStateOf<Throwable?>(null); private set
    var noticesLoading by mutableStateOf(false); private set
    var hasMoreNotices by mutableStateOf(true); private set
    var noticesLoaded by mutableStateOf(false); private set
    var saved by mutableStateOf<List<SavedTopic>>(emptyList()); private set
    var savedError by mutableStateOf<Throwable?>(null); private set
    var savedLoading by mutableStateOf(false); private set
    var hasMoreSaved by mutableStateOf(false); private set
    private var savedPage = 0
    private var noticeOffset = 0
    private var noticeNext: String? = null
    private var sessionEpoch = 0
    private val messages = Channel<String>(Channel.BUFFERED)
    private var noticeJob: Job? = null
    private var savedJob: Job? = null
    private var notificationJob: Job? = null
    private var countJob: Job? = null
    private val ledger = NotificationLedger()
    val events = messages.receiveAsFlow()

    init { viewModelScope.launch { checkSession() } }

    private fun acceptSession(member: Member?) {
        val changed = user?.id != member?.id || user?.username != member?.username
        if (changed) {
            repository.client.invalidateCookieRequests()
            sessionEpoch++
            noticeJob?.cancel(); savedJob?.cancel()
            notices = emptyList(); saved = emptyList()
            noticesError = null; savedError = null; noticesLoading = false; savedLoading = false
            hasMoreNotices = true; noticesLoaded = false; hasMoreSaved = false; noticeOffset = 0; noticeNext = null; savedPage = 0
            notificationJob?.cancel(); countJob?.cancel()
            notificationJob = null; countJob = null
        }
        delivery.account(member?.id)
        ledger.account(member)
        unreadCount = ledger.count
        user = member
        checkingSession = false
        sessionReady = true
        if (member != null && notificationJob == null && updates != null) {
            val epoch = sessionEpoch
            notificationJob = viewModelScope.launch {
                try {
                    updates.notifications(member).collect { batch ->
                        if (epoch != sessionEpoch) return@collect
                        val alerts = ledger.accept(batch).filter { alert ->
                            user?.ignoredUsers.orEmpty().none { it.equals(alert.username, true) }
                        }
                        unreadCount = ledger.count
                        batch.snapshots.forEach { snapshot ->
                            snapshot.notice?.let(delivery::correlate)
                            val readIds = snapshot.recent.filterValues { it }.keys
                            delivery.readForum(readIds, snapshot.count == 0)
                            if (noticesLoaded) {
                                val newest = snapshot.notice
                                if (newest != null) notices = (listOf(newest) + notices.filterNot { it.id == newest.id })
                                notices = notices.map { notice -> ledger.reads[notice.id]?.let { notice.copy(read = it) } ?: notice }
                            }
                        }
                        alerts.forEach(delivery::show)
                        if ((batch.reconnect || alerts.isNotEmpty()) && batch.snapshots.none { it.count != null }) refreshNotificationCount()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiFailure) {
                    if (epoch == sessionEpoch && e.kind == FailureKind.VERIFICATION) {
                        // Push clearance is independent; a normal main-site read owns its recovery UI.
                        notificationJob = null
                    }
                }
            }
        }
    }

    fun recordIgnore(viewerId: Int, username: String, ignored: Boolean) {
        val current = user?.takeIf { it.id == viewerId } ?: return
        val names = current.ignoredUsers.filterNot { it.equals(username, ignoreCase = true) }.toSet()
        user = current.copy(ignoredUsers = if (ignored) names + username else names)
    }

    suspend fun checkSession(): Boolean {
        if (checkingSession) return false
        val epoch = sessionEpoch
        val countRevision = ledger.revision
        checkingSession = true; sessionError = null
        return try {
            resetSession()
            val receivedUser = withContext(Dispatchers.IO) { repository.currentUser() }
            if (epoch != sessionEpoch) false else {
                val same = user?.id == receivedUser?.id
                acceptSession(receivedUser)
                if (same && receivedUser != null) { ledger.freshCount(receivedUser, countRevision); unreadCount = ledger.count }
                if (noticesLoaded && !noticesLoading) loadNotices()
                receivedUser != null
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (epoch == sessionEpoch) {
                if ((e as? ApiFailure)?.kind == FailureKind.SIGN_IN) acceptSession(null) else sessionError = e
            }
            false
        } finally { if (epoch == sessionEpoch) { checkingSession = false; sessionReady = true } }
    }
    fun webSessionBoundary() { repository.client.invalidateCookieRequests() }
    fun loadCategories() {
        if (categoriesLoading) return
        categoriesLoading = true; categoryError = null
        viewModelScope.launch {
            try { categories = withContext(Dispatchers.IO) { repository.categories() } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { categoryError = e }
            finally { categoriesLoading = false }
        }
    }
    fun loadNotices(more: Boolean = false) {
        if (user == null || noticesLoading) return
        val epoch = sessionEpoch
        noticesLoading = true; noticesError = null
        val offset = if (more) noticeOffset else 0
        val requestedRevision = ledger.revision
        noticeJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { repository.noticePage(offset, if (more) noticeNext else null) }
                if (epoch == sessionEpoch) {
                    val reconciled = ledger.reconcilePage(page.notices, requestedRevision)
                    reconciled.forEach(delivery::correlate)
                    notices = (if (more) notices + reconciled else reconciled).distinctBy(Notice::id)
                        .map { notice -> ledger.reads[notice.id]?.let { notice.copy(read = it) } ?: notice }
                    hasMoreNotices = page.next != null; noticeNext = page.next; noticeOffset = offset + 30; noticesLoaded = true
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (epoch == sessionEpoch) noticesError = e }
            finally { if (epoch == sessionEpoch) noticesLoading = false }
        }
    }
    fun markRead(id: Int? = null) {
        if (user == null) return
        if (id != null && (ledger.reads[id] == true || notices.firstOrNull { it.id == id }?.read == true)) return
        val epoch = sessionEpoch
        val revision = ledger.revision
        val ids = id?.let(::setOf) ?: (notices.map { it.id } + ledger.reads.keys).toSet()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.readNotification(id) }
                if (epoch == sessionEpoch) {
                    val unchanged = revision == ledger.revision
                    ledger.confirmRead(ids, id == null, revision)
                    unreadCount = ledger.count
                    notices = notices.map { if (it.id in ids) it.copy(read = true) else it }
                    delivery.readForum(ids, id == null && unchanged)
                    if (updates != null) refreshNotificationCount()
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (epoch == sessionEpoch) messages.send(e.message ?: "标记失败") }
        }
    }
    private fun refreshNotificationCount() {
        val accountId = user?.id ?: return
        if (countJob?.isActive == true) return
        val epoch = sessionEpoch
        val revision = ledger.revision
        countJob = viewModelScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { repository.currentUser() }
                if (epoch == sessionEpoch && fresh?.id == accountId) {
                    ledger.freshCount(fresh, revision); unreadCount = ledger.count
                    if (unreadCount == 0) delivery.readForum(emptySet(), all = true)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    fun readNotificationTarget(target: NotificationTarget) {
        if (user?.id != target.accountId) return
        target.noticeId?.let { markRead(it); return }
        if (target.topicId == null && target.chatChannelId == null) return
        val epoch = sessionEpoch
        fun matches(notice: Notice) = if (target.chatChannelId != null) notice.chatChannelId == target.chatChannelId &&
            (target.chatMessageId == null || notice.chatMessageId == target.chatMessageId)
        else notice.topicId == target.topicId && (target.postNumber == null || notice.postNumber == target.postNumber)
        notices.firstOrNull(::matches)?.let { markRead(it.id); return }
        viewModelScope.launch {
            try {
                var next: String? = null
                for (pageIndex in 0..2) {
                    val page = withContext(Dispatchers.IO) { repository.noticePage(pageIndex * 30, next) }
                    if (epoch != sessionEpoch) return@launch
                    page.notices.forEach(delivery::correlate)
                    page.notices.firstOrNull(::matches)?.let { markRead(it.id); return@launch }
                    next = page.next ?: break
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    fun chatRead(channelId: Int, messageId: Int) { delivery.readChat(channelId, messageId); refreshNotificationCount() }
    fun loadSaved(more: Boolean = false) {
        val username = user?.username ?: return
        if (savedLoading) return
        val epoch = sessionEpoch
        savedLoading = true; savedError = null
        val page = if (more) savedPage + 1 else 0
        savedJob = viewModelScope.launch {
            try {
                val (result, hasMore) = withContext(Dispatchers.IO) { repository.bookmarks(username, page) }
                if (epoch == sessionEpoch) { saved = (if (more) saved + result else result).distinctBy(SavedTopic::id); hasMoreSaved = hasMore; savedPage = page }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (epoch == sessionEpoch) savedError = e }
            finally { if (epoch == sessionEpoch) savedLoading = false }
        }
    }
    fun logout() {
        val name = user?.username
        repository.client.invalidateCookieRequests()
        sessionEpoch++
        notificationJob?.cancel(); countJob?.cancel(); noticeJob?.cancel(); savedJob?.cancel()
        notificationJob = null; countJob = null
        ledger.account(null); unreadCount = 0; delivery.account(null)
        checkingSession = true
        user = null; notices = emptyList(); saved = emptyList(); sessionError = null
        noticesError = null; savedError = null; noticesLoading = false; savedLoading = false
        viewModelScope.launch {
            if (name != null) withTimeoutOrNull(5000) {
                try { withContext(Dispatchers.IO) { repository.client.write("DELETE", "/session/${android.net.Uri.encode(name)}") } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
            try { clearSession() } finally { checkingSession = false }
            messages.send("已退出登录并清除本机账号数据")
        }
    }
}
