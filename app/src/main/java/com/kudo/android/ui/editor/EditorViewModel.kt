package com.kudo.android.ui.editor

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kudo.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class EditorViewModel(private val app: AppContainer, userId: Int, val topicId: Int?, val replyTo: Int?,
    private val publishRequest: suspend (Draft) -> PublishResult = { draft -> app.repository.publish(draft.raw, draft.title, draft.categoryId.takeIf { it > 0 }, topicId, replyTo, draft.tags) },
    categoryId: Int? = null,
    private val rulesRequest: suspend () -> ReplyRules = { app.repository.replyRules(requireNotNull(topicId), categoryId) },
) : ViewModel() {
    private val draftKey = "${userId}_${topicId ?: "new"}_${if (topicId == null) categoryId?.takeIf { it > 0 } ?: 0 else replyTo ?: 0}"
    var draft by mutableStateOf(app.settings.draft(draftKey).let { saved ->
        if (topicId == null && saved.categoryId == 0 && categoryId != null && categoryId > 0) saved.copy(categoryId = categoryId) else saved
    }); private set
    var sending by mutableStateOf(false); private set
    var uploading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var replyRules by mutableStateOf<ReplyRules?>(null); private set
    var rulesLoading by mutableStateOf(false); private set
    var rulesError by mutableStateOf<Throwable?>(null); private set
    init { if (topicId != null) reloadReplyRules() }
    fun reloadReplyRules() {
        if (topicId == null || rulesLoading || sending) return
        rulesLoading = true; rulesError = null
        viewModelScope.launch {
            try { replyRules = withContext(Dispatchers.IO) { rulesRequest() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { rulesError = e }
            finally { rulesLoading = false }
        }
    }
    private val results = Channel<PublishResult>(Channel.BUFFERED)
    val published = results.receiveAsFlow()
    suspend fun searchTags(query: String, categoryId: Int, selected: List<TopicTag>): List<TopicTag> =
        app.repository.composerTags(query, categoryId, selected)
    private val imageInsertion = EditorImageInsertion()
    fun prepareImageUpload(selection: TextRange) { if (!sending && !uploading) imageInsertion.prepare(draft.raw, selection) }

    fun edit(value: Draft) { if (!sending) { imageInsertion.edited(draft.raw, value.raw); draft = value; app.settings.saveDraft(draftKey, value); error = null } }
    fun publish() {
        if (sending || uploading) return
        if (draft.raw.isBlank()) { error = "先写点内容吧"; return }
        if (topicId != null) {
            val rules = replyRules
            if (rules == null) {
                error = rulesError?.let { it.message ?: "读取回帖字数规则失败" } ?: "正在读取回帖字数规则，请稍候"
                return
            }
            rules.problem(draft.raw)?.let { error = it; return }
        }
        if (topicId == null && (draft.title.isBlank() || draft.categoryId == 0)) { error = "请填写标题并选择类别"; return }
        sending = true; error = null
        val snapshot = draft
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { publishRequest(snapshot) }
                app.settings.clearDraft(draftKey)
                draft = Draft()
                sending = false
                results.send(result)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "发布失败，草稿已保留"; sending = false }
        }
    }
    fun upload(context: Context, uri: Uri) {
        if (uploading || sending) return
        uploading = true; error = null
        viewModelScope.launch {
            try {
                val markdown = withContext(Dispatchers.IO) {
                    uploadEditorImage(app.repository, context, uri)
                }
                edit(draft.copy(raw = imageInsertion.insert(draft.raw, markdown)))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "上传失败" }
            finally { uploading = false }
        }
    }
}
