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

/** Independent answers plus a shared notebook-page view, with save-before-navigation. */
class QuestionSession(val set: QuestionSetRepository.LoadedSet, val sourcePdf: File,
    val answers: QuestionAnswerSession? = null,
    val annotations: QuestionAnswerSession? = null,
    val tools: QuestionTools? = null,
    private val scope: CoroutineScope = MainScope(),
    private val progressStore: QuestionProgressStore? = null,
    initialProgress: QuestionProgress = QuestionProgress(),
) {
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
    val busy get() = transitioning || hosts.any { it.busy }
    val count get() = set.entries.size
    val current get() = set.entries.getOrNull(index)
    val canPrevious get() = index > 0 && !busy
    val canNext get() = index + 1 < count && !busy
    init { hosts.forEach { it.switchTo(current?.question?.id) } }
    private suspend fun settled() { hosts.forEach { host -> snapshotFlow { host.busy }.first { !it } } }
    private fun operation(action: suspend () -> Unit) {
        transitioning = true
        hosts.forEach { it.holdInput() }
        scope.launch {
            try {
                settled()
                hosts.forEach { it.holdInput() }
                var saved = true
                for (host in hosts) if (!host.prepareTransition()) saved = false
                if (!persistProgress()) saved = false
                if (saved) action()
            } finally {
                transitioning = false
                hosts.forEach { it.resumeInput() }
                val back = pendingBack
                pendingBack = null
                if (back != null) { if (closed) back() else close(back) }
            }
        }
    }
    private fun move(target: Int) {
        if (hosts.isEmpty()) {
            index = target
            if (progressStore != null) scope.launch { persistProgress() }
            return
        }
        operation {
            hosts.forEach { it.switchTo(set.entries[target].question?.id) }
            settled()
            index = target
            tools?.refresh()
            persistProgress()
        }
    }
    fun previous() { if (canPrevious) move(index - 1) }
    fun next() { if (canNext) move(index + 1) }
    fun background() {
        if (closed) return
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
    fun retryProgress() { if (!busy && !closed) scope.launch { persistProgress() } }
    fun setAnswerOptions(options: QuestionAnswerOptions) {
        if (busy || closed) return
        val id = current?.question?.id ?: return
        val choices = progress.choices.filterNot { (key, choice) -> key == id && choice !in options.choices }
        progress = progress.copy(choices = choices, answerOptions = progress.answerOptions + (id to options))
        if (progressStore != null) scope.launch { persistProgress() }
    }
    private suspend fun persistProgress(): Boolean = progressWrites.withLock {
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
    fun close(onClosed: () -> Unit) {
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
