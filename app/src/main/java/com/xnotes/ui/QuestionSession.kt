package com.xnotes.ui

import androidx.compose.runtime.*
import com.xnotes.platform.QuestionSetRepository
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Two independently persisted ink documents, with coordinated save-before-navigation. */
class QuestionSession(val set: QuestionSetRepository.LoadedSet, val sourcePdf: File,
    val answers: QuestionAnswerSession? = null,
    val annotations: QuestionAnswerSession? = null,
    val tools: QuestionTools? = null,
    private val scope: CoroutineScope = MainScope(),
) {
    var index by mutableIntStateOf(0)
        private set
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
        if (hosts.isEmpty()) { index = target; return }
        operation {
            hosts.forEach { it.switchTo(set.entries[target].question?.id) }
            settled()
            index = target
            tools?.refresh()
        }
    }
    fun previous() { if (canPrevious) move(index - 1) }
    fun next() { if (canNext) move(index + 1) }
    fun background() { hosts.forEach { it.background() } }
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
