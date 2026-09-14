package com.xnotes.ui

import com.xnotes.core.model.*
import com.xnotes.platform.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QuestionProgressSessionTest {
    @Test fun questionTypesAndCountsPersistIndependentlyAndClearIncompatibleChoices() = runBlocking {
        val store = Store()
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val session = QuestionSession(entries(), File("pdf"), scope = work, progressStore = store)
            session.setAnswerOptions(QuestionAnswerOptions(optionCount = 8))
            session.selectChoice("H")
            until { store.state.choices["a"] == "H" }
            session.next()
            assertEquals(5, session.answerOptions.optionCount)
            session.selectChoice("B")
            session.previous()
            assertEquals("H", session.selectedChoice)
            session.setAnswerOptions(QuestionAnswerOptions(optionCount = 2))
            assertNull(session.selectedChoice)
            session.selectChoice("A")
            session.setAnswerOptions(QuestionAnswerOptions(QuestionType.OPEN_ENDED, 2))
            assertNull(session.selectedChoice)
            assertTrue(session.answerOptions.choices.isEmpty())
            until { store.state.optionsFor("a").type == QuestionType.OPEN_ENDED && store.state.choices["a"] == null }
            val restored = QuestionSession(entries(), File("pdf"), scope = work, initialProgress = store.load())
            assertEquals(QuestionType.OPEN_ENDED, restored.answerOptions.type)
            restored.next()
            assertEquals("B", restored.selectedChoice)
            assertEquals(5, restored.answerOptions.optionCount)
        } finally { work.cancel() }
    }
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
