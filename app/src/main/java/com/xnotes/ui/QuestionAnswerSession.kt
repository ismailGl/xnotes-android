package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.core.model.Document
import com.xnotes.platform.AnswerStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small host contract lets save/switch ordering be tested without Android Views. */
interface AnswerSurface {
    val document: Document
    val history: com.xnotes.core.history.History? get() = null
    var inputEnabled: Boolean
    fun finishInput()
    fun snapshot(): Document
    fun dispose()
}

/** One live canvas. All state/snapshots are on Main; AnswerStore owns background IO. */
class QuestionAnswerSession(
    private val store: AnswerStore,
    private val createSurface: (Document, com.xnotes.core.history.History?, () -> Unit) -> AnswerSurface,
    private val scope: CoroutineScope = MainScope(),
    private val debounceMs: Long = 600,
    private val memory: QuestionHistoryCache? = null,
    private val memoryPrefix: String = "",
) {
    var surface by mutableStateOf<AnswerSurface?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var answerId: String? = null
    private var revision = 0L
    private var savedRevision = -1L
    private var generation = 0L
    private var debounce: Job? = null
    private val writes = Mutex()
    private var closed = false
    private var held = false
    private var pendingClose: (() -> Unit)? = null

    private fun changed(gen: Long) {
        if (gen != generation || closed) return
        revision++
        surface?.document?.dirty = true
        debounce?.cancel()
        debounce = scope.launch {
            delay(debounceMs)
            // The actual write is a separate job: editing only cancels the debounce, never IO.
            scope.launch { saveCurrent() }
        }
    }

    private suspend fun saveCurrent(): Boolean = writes.withLock {
        val canvas = surface ?: return@withLock true
        val id = answerId ?: return@withLock true
        if (revision == savedRevision) return@withLock true
        val version = revision
        try {
            val snapshot = canvas.snapshot()
            store.save(id, snapshot)
            if (surface === canvas) {
                savedRevision = version
                if (revision == version) canvas.document.dirty = false
            }
            error = null
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            error = "Could not save this answer. Your ink is still open; retry before leaving."
            false
        }
    }

    fun freezeInput() {
        surface?.inputEnabled = false
        surface?.finishInput()
        debounce?.cancel()
    }

    suspend fun prepareTransition(): Boolean { freezeInput(); return saveCurrent() }
    fun holdInput() { held = true; freezeInput() }
    fun resumeInput() { held = false; surface?.inputEnabled = !busy }

    private fun releaseSurface() {
        val canvas = surface ?: return
        val id = answerId
        canvas.dispose()
        if (id != null) canvas.history?.let { memory?.retain(memoryPrefix + id, canvas.document, it) }
        surface = null
    }

    fun switchTo(id: String?, onReady: () -> Unit = {}) {
        if (closed || busy) return // rapid taps cannot skip a save or start overlapping loads
        busy = true
        freezeInput()
        scope.launch {
            try {
                if (!saveCurrent()) return@launch
                releaseSurface()
                generation++
                answerId = id
                revision = 0
                savedRevision = -1
                error = null
                if (id != null) {
                    try {
                        val cached = memory?.take(memoryPrefix + id)
                        val document = cached?.document ?: store.load(id)
                        val gen = generation
                        surface = createSurface(document, cached?.history) { changed(gen) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Could not load this answer. Its file was left unchanged. Retry or choose another question." }
                }
                onReady()
            } finally { completeOperation() }
        }
    }

    fun retry() {
        if (surface == null) switchTo(answerId)
        else if (!busy) scope.launch { saveCurrent() }
    }

    fun background() {
        if (closed) return
        surface?.finishInput()
        debounce?.cancel()
        scope.launch { saveCurrent() }
    }

    fun close(onClosed: () -> Unit) {
        if (closed) { onClosed(); return }
        if (busy) { pendingClose = onClosed; return }
        busy = true
        freezeInput()
        scope.launch {
            try {
                if (!saveCurrent()) return@launch
                releaseSurface()
                generation++
                closed = true
                onClosed()
            } finally {
                completeOperation()
                if (closed) scope.cancel()
            }
        }
    }

    private fun completeOperation() {
        busy = false
        surface?.inputEnabled = !held
        val callback = pendingClose
        pendingClose = null
        if (callback != null) close(callback)
    }
}
