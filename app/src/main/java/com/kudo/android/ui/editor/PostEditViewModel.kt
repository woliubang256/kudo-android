package com.kudo.android.ui.editor

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException

class PostEditViewModel(
    private val loadPost: suspend () -> EditablePost,
    private val savePost: suspend (EditablePost, PostEditDraft) -> Post,
    restore: () -> PostEditDraft? = { null },
    private val persist: (PostEditDraft?) -> Unit = {},
) : ViewModel() {
    var source by mutableStateOf<EditablePost?>(null); private set
    var draft by mutableStateOf(restore()); private set
    var loading by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var uploading by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var conflict by mutableStateOf(false); private set
    var writeLocked by mutableStateOf(false); private set
    private val results = Channel<PostEditResult>(Channel.BUFFERED)
    val saved = results.receiveAsFlow()
    private val imageInsertion = EditorImageInsertion()
    fun prepareImageUpload(selection: TextRange) { if (!loading && !sending && !uploading) draft?.let { imageInsertion.prepare(it.raw, selection) } }
    val canSave get() = source != null && !loading && !sending && !uploading && !conflict && !writeLocked &&
        draft?.let { it.raw.isNotBlank() && (source?.post?.number != 1 || it.title.isNotBlank()) &&
            (it.raw != it.originalRaw || it.title != it.originalTitle) } == true

    init { load() }
    fun load() {
        if (loading || sending || uploading) return
        val checking = writeLocked
        loading = true; error = null; source = null
        viewModelScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { loadPost() }
                source = latest
                val savedDraft = draft
                if (checking && savedDraft != null && latest.raw == savedDraft.raw && latest.title == savedDraft.title) {
                    complete(latest.post, latest.topicId, savedDraft.title)
                } else {
                    if (savedDraft == null) draft = PostEditDraft.fromPost(latest)
                    conflict = draft?.let { it.originalRaw != latest.raw || it.originalTitle != latest.title } == true
                    writeLocked = false
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e }
            finally { loading = false }
        }
    }
    fun useLatest() {
        if (loading || sending || uploading || writeLocked) return
        source?.let { draft = PostEditDraft.fromPost(it); persist(null); conflict = false; error = null }
    }
    fun edit(value: PostEditDraft) {
        if (loading || sending) return
        imageInsertion.edited(draft?.raw.orEmpty(), value.raw)
        draft = value; persist(value); error = null
    }
    fun save() {
        if (!canSave) return
        val snapshot = draft ?: return
        val target = source ?: return
        sending = true; error = null
        viewModelScope.launch {
            try {
                val post = withContext(Dispatchers.IO) { savePost(target.copy(raw = snapshot.originalRaw, title = snapshot.originalTitle), snapshot) }
                complete(post, target.topicId, snapshot.title)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = e
                writeLocked = (e as? ApiFailure)?.kind == FailureKind.NETWORK || e.message.orEmpty().contains("尚未确认") ||
                    (e is IOException && e !is ApiFailure)
            } finally { sending = false }
        }
    }
    fun upload(request: suspend () -> String) {
        if (loading || sending || uploading || draft == null) return
        uploading = true
        viewModelScope.launch {
            try {
                val markdown = withContext(Dispatchers.IO) { request() }
                draft?.let { edit(it.copy(raw = imageInsertion.insert(it.raw, markdown))) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e }
            finally { uploading = false }
        }
    }
    private fun complete(post: Post, topicId: Int, title: String) {
        persist(null); draft = null; writeLocked = false; conflict = false
        results.trySend(PostEditResult(topicId, post, title))
    }
}
