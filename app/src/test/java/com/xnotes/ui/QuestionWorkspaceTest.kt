package com.xnotes.ui

import androidx.compose.runtime.snapshots.Snapshot
import com.xnotes.core.model.*
import com.xnotes.core.history.History
import com.xnotes.core.history.AddItem
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.platform.AnswerStore
import com.xnotes.platform.QuestionSetRepository
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QuestionWorkspaceTest {
    private class Store : AnswerStore {
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val saved = mutableListOf<String>()
        override suspend fun load(answerId: String) = Document.blank()
        override suspend fun save(answerId: String, document: Document) {
            gate?.await()
            if (fail) error("Disk full")
            saved += answerId
        }
    }
    private class Surface(override val document: Document, override val history: History = History(),
        val changed: () -> Unit = {}) : AnswerSurface {
        override var inputEnabled = true
        var disposed = false
        var finished = 0
        override fun finishInput() { finished++ }
        override fun snapshot() = document.snapshot()
        override fun dispose() { disposed = true }
        fun draw() {
            val page = document.pages.single()
            val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(20.0, 30.0, 0.6)))
            page.items.add(stroke)
            history.push(AddItem(page, stroke))
            changed()
        }
    }
    private fun entries() = QuestionSetRepository.LoadedSet("set", "test", listOf("a", "b", "c").map {
        QuestionSetRepository.Entry(Question(it, 0, NormalizedRect(0.0, 0.0, 1.0, 1.0)))
    })
    private suspend fun until(predicate: () -> Boolean) = withTimeout(3000) {
        while (!predicate()) { Snapshot.sendApplyNotifications(); delay(1) }
    }
    @Test fun backDuringSwitchSavesBothLayersAndReentryRestoresTheirIndependentHistories() = runBlocking {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        fun child() = CoroutineScope(work.coroutineContext + SupervisorJob(work.coroutineContext[Job]))
        val memory = QuestionHistoryCache()
        val answerStore = Store()
        val annotationStore = Store()
        fun host(store: Store, area: String) = QuestionAnswerSession(store,
            { d, h, changed -> Surface(d, h ?: History(), changed) }, child(),
            memory = memory, memoryPrefix = "set/$area/")
        try {
            val answer = host(answerStore, "answers")
            val annotation = host(annotationStore, "annotations")
            val first = QuestionSession(entries(), File("source.pdf"), answer, annotation, scope = child())
            until { !first.busy }
            val a = answer.surface as Surface
            val q = annotation.surface as Surface
            a.draw(); q.draw()
            annotationStore.gate = CompletableDeferred()
            first.next()
            var closed = false
            first.close { closed = true }
            assertFalse(closed)
            annotationStore.gate!!.complete(Unit)
            until { closed }
            assertTrue(a.disposed && q.disposed)
            assertNull(answer.surface)
            assertNull(annotation.surface)
            assertEquals(listOf("a", "b"), answerStore.saved)
            assertEquals(listOf("a", "b"), annotationStore.saved)

            val reopenedAnswer = host(answerStore, "answers")
            val reopenedAnnotation = host(annotationStore, "annotations")
            val second = QuestionSession(entries(), File("source.pdf"), reopenedAnswer, reopenedAnnotation, scope = child())
            until { !second.busy }
            val restoredA = reopenedAnswer.surface as Surface
            val restoredQ = reopenedAnnotation.surface as Surface
            assertSame(a.history, restoredA.history)
            assertSame(q.history, restoredQ.history)
            assertNotSame(restoredA.history, restoredQ.history)
            restoredQ.history.undo(); restoredQ.changed()
            assertTrue(restoredQ.document.pages.single().items.isEmpty())
            assertEquals(1, restoredA.document.pages.single().items.size)
            restoredQ.history.redo(); restoredQ.changed()
            assertEquals(1, restoredQ.document.pages.single().items.size)
            closed = false
            second.close { closed = true }
            until { closed }
        } finally { work.cancel() }
    }
    @Test fun failedAnnotationSavePreventsBothCanvasesBeingReleasedAndBlocksBack() = runBlocking {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val answerStore = Store()
        val annotationStore = Store()
        val answer = QuestionAnswerSession(answerStore, { d, _, _ -> Surface(d) }, work)
        val annotation = QuestionAnswerSession(annotationStore, { d, _, _ -> Surface(d) }, work)
        val session = QuestionSession(entries(), File("source.pdf"), answer, annotation, scope = work)
        try {
            until { !session.busy }
            val a = answer.surface as Surface
            val q = annotation.surface as Surface
            annotationStore.fail = true
            session.next()
            until { !session.busy }
            assertEquals(0, session.index)
            assertSame(a, answer.surface)
            assertSame(q, annotation.surface)
            assertFalse(a.disposed)
            assertFalse(q.disposed)
            assertTrue(a.inputEnabled && q.inputEnabled)
            assertTrue(a.finished > 0 && q.finished > 0)
            assertNotNull(annotation.error)
            var closed = false
            session.close { closed = true }
            until { !session.busy }
            assertFalse(closed)
            annotationStore.fail = false
            // Each host normally owns its own scope; use navigation here so the shared test
            // scope is not cancelled by the first host's successful close.
            session.next()
            until { !session.busy }
            assertEquals(1, session.index)
            assertTrue(a.disposed && q.disposed)
            assertTrue("a" in answerStore.saved && "a" in annotationStore.saved)
        } finally { work.cancel() }
    }

    @Test fun rapidNavigationCannotMixQuestionDocumentsAndInputStaysFrozenDuringLoad() = runBlocking {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val answerStore = Store()
        val annotationStore = Store()
        val answer = QuestionAnswerSession(answerStore, { d, _, _ -> Surface(d) }, work)
        val annotation = QuestionAnswerSession(annotationStore, { d, _, _ -> Surface(d) }, work)
        val session = QuestionSession(entries(), File("source.pdf"), answer, annotation, scope = work)
        try {
            until { !session.busy }
            annotationStore.gate = CompletableDeferred()
            session.next()
            session.next()
            session.previous()
            assertFalse(answer.surface!!.inputEnabled)
            assertFalse(annotation.surface!!.inputEnabled)
            assertEquals(0, session.index)
            annotationStore.gate!!.complete(Unit)
            until { !session.busy }
            assertEquals(1, session.index)
            assertTrue(answer.surface!!.inputEnabled)
            assertTrue(annotation.surface!!.inputEnabled)
            assertEquals(listOf("a"), annotationStore.saved)
        } finally { work.cancel() }
    }
}
