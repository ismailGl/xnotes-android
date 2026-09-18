package com.xnotes.ui

import androidx.compose.runtime.*
import com.xnotes.platform.QuestionSetRepository
import com.xnotes.platform.QuestionProgress
import com.xnotes.platform.QuestionProgressStore
import com.xnotes.platform.QuestionAnswerOptions
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Independent answers plus a separate question scratch canvas, with save-before-navigation. */
enum class QuestionPeek { FOCUSED, FADED }

class QuestionSession(set: QuestionSetRepository.LoadedSet, val sourcePdf: File,
    val answers: QuestionAnswerSession? = null,
    val annotations: QuestionAnswerSession? = null,
    val tools: QuestionTools? = null,
    private val scope: CoroutineScope = MainScope(),
    private val progressStore: QuestionProgressStore? = null,
    initialProgress: QuestionProgress = QuestionProgress(),
    private val beforeTransition: (suspend () -> Unit)? = null,
    private val onQuestionChanged: () -> Unit = {},
    private val onNavigate: suspend (com.xnotes.core.model.Question?) -> Unit = {},
    private val onDelete: suspend (String) -> Unit = {},
    private val onInputEnabled: (Boolean) -> Unit = {},

) {
    var set by mutableStateOf(set)
        private set
    var peek by mutableStateOf(QuestionPeek.FOCUSED)
        private set
    var editingCrop by mutableStateOf(false)
    var savingCrop by mutableStateOf(false)
    var loadingView by mutableStateOf(false)
    var confirmDelete by mutableStateOf(false)
    private var deletePending = false
    val feedback get() = progress.feedback
    val results get() = progress.results
    val currentComplete get() = current?.question?.id?.let { it in progress.completed || it in progress.choices } == true
    val review get() = current?.question?.let { QuestionPageReview.create(it.sourcePageIndex, set.entries.mapNotNull { it.question }, progress) }
    fun markComplete() {
        if (busy || closed) return
        val id = current?.question?.id ?: return
        progress = progress.copy(completed = progress.completed + id)
        scope.launch { persistProgress() }
    }
    fun showPageReview() {
        if (busy || closed) return
        peek = QuestionPeek.FADED
        onQuestionChanged()
    }
    val pageComplete get() = current?.question?.let { q -> set.entries.mapNotNull { it.question }
        .filter { it.sourcePageIndex == q.sourcePageIndex }.all { it.id in progress.choices || it.id in progress.completed } } ?: false
    fun cyclePeek() {
        if (busy) return
        peek = QuestionPeek.entries[(peek.ordinal + 1) % QuestionPeek.entries.size]
        onQuestionChanged()
    }
    fun setFeedback(value: com.xnotes.platform.QuestionFeedback) {
        if (busy || closed) return
        progress = progress.copy(feedback = value)
        scope.launch { persistProgress() }
    }
    fun replaceCrop(question: com.xnotes.core.model.Question) {
        set = set.copy(entries = set.entries.map { if (it.question?.id == question.id) it.copy(question = question) else it })
        editingCrop = false
        onQuestionChanged()
    }
    var index by mutableIntStateOf(set.entries.indexOfFirst { it.question?.id == initialProgress.lastQuestionId && it.question != null }.coerceAtLeast(0))
        private set
    private var progress by mutableStateOf(initialProgress)
    private var savedProgress = initialProgress
    private val progressWrites = Mutex()
    var progressError by mutableStateOf<String?>(null)
        private set
    val selectedChoice get() = current?.question?.id?.let { progress.choices[it] }
    val answerOptions get() = current?.question?.id?.let(progress::optionsFor) ?: QuestionAnswerOptions()
    var split by mutableFloatStateOf(0.38f)
    private var transitioning by mutableStateOf(false)
    private var pendingBack: (() -> Unit)? = null
    private var closed = false
    private val hosts get() = listOfNotNull(answers, annotations)
    val busy get() = transitioning || loadingView || savingCrop || hosts.any { it.busy }
    val count get() = set.entries.size
    val current get() = set.entries.getOrNull(index)
    val canPrevious get() = index > 0 && !busy
    val canNext get() = index + 1 < count && !busy
    init { hosts.forEach { it.switchTo(current?.question?.id) } }
    private suspend fun settled() { hosts.forEach { host -> snapshotFlow { host.busy }.first { !it } } }
    private fun operation(action: suspend () -> Unit) {
        transitioning = true
        onInputEnabled(false)
        hosts.forEach { it.holdInput() }
        scope.launch {
            try {
                settled()
                hosts.forEach { it.holdInput() }
                var saved = true
                try { if (!deletePending) beforeTransition?.invoke() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { progressError = "Could not save question workspace. Retry before leaving."; saved = false }
                for (host in hosts) if (!host.prepareTransition()) saved = false
                if (saved && !deletePending && !persistProgress()) saved = false
                if (saved) { progressError = null; action() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { progressError = "Could not complete the question operation. Retry before leaving." }
            finally {
                transitioning = false
                if (!closed) onInputEnabled(true)
                hosts.forEach { it.resumeInput() }
                val back = pendingBack
                pendingBack = null
                if (back != null) { if (closed) back() else close(back) }
            }
        }
    }
    private fun move(target: Int) {
        if (hosts.isEmpty() && beforeTransition == null) {
            index = target
            peek = QuestionPeek.FOCUSED
            onQuestionChanged()
            if (progressStore != null) scope.launch { persistProgress() }
            return
        }
        operation {
            hosts.forEach { it.switchTo(set.entries[target].question?.id) }
            settled()
            onNavigate(set.entries[target].question)
            index = target
            peek = QuestionPeek.FOCUSED
            onQuestionChanged()
            tools?.refresh()
            persistProgress()
        }
    }
    fun previous() { if (canPrevious) move(index - 1) }
    fun next() { if (canNext) move(index + 1) }
    fun background() {
        if (closed || deletePending) return
        hosts.forEach { it.background() }
        if (progressStore != null) scope.launch { persistProgress() }
    }
    fun selectChoice(choice: String) {
        require(choice in answerOptions.choices)
        if (busy || closed) return
        val id = current?.question?.id ?: return
        val choices = progress.choices.toMutableMap()
        if (choices[id] == choice) choices.remove(id) else choices[id] = choice
        progress = progress.copy(choices = choices)
        if (progressStore != null) scope.launch { persistProgress() }
    }
    fun retryProgress() { if (!busy && !closed) { if (deletePending) deleteCurrent() else operation { progressError = null } } }
    fun setAnswerOptions(options: QuestionAnswerOptions) {
        if (busy || closed) return
        val id = current?.question?.id ?: return
        val choices = progress.choices.filterNot { (key, choice) -> key == id && choice !in options.choices }
        progress = progress.copy(choices = choices, answerOptions = progress.answerOptions + (id to options))
        if (progressStore != null) scope.launch { persistProgress() }
    }
    private suspend fun persistProgress(): Boolean = progressWrites.withLock {
        if (deletePending) return@withLock false
        val store = progressStore ?: return@withLock true
        val snapshot = progress.copy(lastQuestionId = current?.question?.id)
        if (snapshot == savedProgress) return@withLock true
        try {
            store.save(snapshot)
            savedProgress = snapshot
            progressError = null
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            progressError = "Could not save the question position, type or choice. Retry before leaving."
            false
        }
    }
    fun deleteCurrent() {
        if (busy || closed) return
        val question = current?.question ?: return
        operation {
            progressWrites.withLock {
            deletePending = true
            onDelete(question.id)
            val entries = set.entries.filterNot { it.question?.id == question.id }
            val nextIndex = index.coerceAtMost((entries.size - 1).coerceAtLeast(0))
            set = set.copy(entries = entries)
            index = nextIndex
            progress = progress.copy(lastQuestionId = current?.question?.id,
                choices = progress.choices - question.id, answerOptions = progress.answerOptions - question.id,
                results = progress.results - question.id, completed = progress.completed - question.id)
            savedProgress = progress.copy(lastQuestionId = null)
            deletePending = false
            }
            confirmDelete = false
            peek = QuestionPeek.FOCUSED
            onNavigate(current?.question)
            onQuestionChanged()
            persistProgress()
        }
    }

    fun close(onClosed: () -> Unit) {
        if (deletePending) { retryProgress(); return }
        if (closed) { onClosed(); return }
        if (transitioning) { pendingBack = onClosed; return }
        operation {
            hosts.forEach { host ->
                val done = CompletableDeferred<Unit>()
                host.close { done.complete(Unit) }
                done.await()
            }
            closed = true
            onClosed()
            scope.cancel()
        }
    }
}
