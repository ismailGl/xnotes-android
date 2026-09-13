package com.xnotes.ui

import androidx.compose.runtime.snapshots.Snapshot
import com.xnotes.core.model.*
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
    private class Surface(override val document: Document) : AnswerSurface {
        override var inputEnabled = true
        var disposed = false
        var finished = 0
        override fun finishInput() { finished++ }
        override fun snapshot() = document.snapshot()
        override fun dispose() { disposed = true }
    }
    private fun entries() = QuestionSetRepository.LoadedSet("set", "test", listOf("a", "b", "c").map {
        QuestionSetRepository.Entry(Question(it, 0, NormalizedRect(0.0, 0.0, 1.0, 1.0)))
    })
    private suspend fun until(predicate: () -> Boolean) = withTimeout(3000) {
        while (!predicate()) { Snapshot.sendApplyNotifications(); delay(1) }
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
