package com.xnotes.ui

import com.xnotes.core.model.*
import com.xnotes.platform.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QuestionProgressSessionTest {
    private class Store : QuestionProgressStore {
        var state = QuestionProgress()
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load() = state
        override suspend fun save(progress: QuestionProgress) {
            gate?.await()
            if (fail) error("Write failed")
            state = progress
        }
    }
    private fun entries(ids: List<String> = listOf("a", "b", "c")) = QuestionSetRepository.LoadedSet("set", "Title", ids.map {
        QuestionSetRepository.Entry(Question(it, 0, NormalizedRect(0.0, 0.0, 1.0, 1.0)))
    })
    private suspend fun until(test: () -> Boolean) = withTimeout(3000) { while (!test()) yield() }

    @Test fun restoresByQuestionIdAndPersistsChoiceChangesAndClearing() = runBlocking {
        val store = Store().apply { state = QuestionProgress("b", mapOf("b" to "D")) }
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val session = QuestionSession(entries(), File("pdf"), scope = work, progressStore = store, initialProgress = store.load())
        try {
            assertEquals(1, session.index)
            assertEquals("D", session.selectedChoice)
            session.selectChoice("E")
            session.selectChoice("C")
            until { store.state.choices["b"] == "C" }
            session.next()
            until { store.state.lastQuestionId == "c" }
            assertNull(session.selectedChoice)
            session.previous()
            assertEquals("C", session.selectedChoice)
            session.selectChoice("C")
            until { store.state.choices.isEmpty() && store.state.lastQuestionId == "b" }
            var closed = false
            session.close { closed = true }
            until { closed }
        } finally { work.cancel() }
        val restoredWork = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val restored = QuestionSession(entries(listOf("b", "a", "c")), File("pdf"), scope = restoredWork,
                progressStore = store, initialProgress = store.load())
            assertEquals(0, restored.index) // stable identity, not the former numeric index
            assertNull(restored.selectedChoice)
        } finally { restoredWork.cancel() }
    }

    @Test fun failedStateSaveKeepsSessionOpenAndRetrySavesLatestChoice() = runBlocking {
        val store = Store().apply { fail = true }
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val session = QuestionSession(entries(), File("pdf"), scope = work, progressStore = store)
        try {
            session.selectChoice("B")
            until { session.progressError != null }
            var closed = false
            session.close { closed = true }
            until { !session.busy }
            assertFalse(closed)
            store.fail = false
            store.gate = CompletableDeferred()
            session.selectChoice("D")
            yield()
            session.selectChoice("E")
            session.close { closed = true }
            store.gate!!.complete(Unit)
            until { closed }
            assertEquals("E", store.state.choices["a"])
            assertEquals("a", store.state.lastQuestionId)
            assertNull(session.progressError)
        } finally { work.cancel() }
    }
}
