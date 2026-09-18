package com.xnotes.ui

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question
import com.xnotes.platform.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QuestionEditorSessionTest {
    private fun set() = QuestionSetRepository.LoadedSet("set", "Title", listOf("a", "b").map {
        QuestionSetRepository.Entry(Question(it, 0, NormalizedRect(.1, .2, .8, .9)))
    })

    @Test fun normalEditorMustSaveBeforeNavigationAndClose() = runBlocking {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        var changes = 0
        try {
            val session = QuestionSession(set(), File("source"), scope = work,
                beforeTransition = { saves++; gate.await() }, onQuestionChanged = { changes++ })
            session.next()
            yield()
            assertTrue(session.busy)
            assertEquals(0, session.index)
            session.next()
            gate.complete(Unit)
            yield()
            assertEquals(1, session.index)
            assertEquals(1, changes)
            val closed = CompletableDeferred<Unit>()
            session.close { closed.complete(Unit) }
            closed.await()
            assertEquals(2, saves)
        } finally { work.cancel() }
    }

    @Test fun peekAndCropUpdatesPreserveChoiceIdentityWithoutSavingInk() = runBlocking {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        var saves = 0
        try {
            val session = QuestionSession(set(), File("source"), scope = work,
                initialProgress = QuestionProgress(choices = mapOf("a" to "C")), beforeTransition = { saves++ })
            repeat(2) { session.cyclePeek() }
            assertEquals(QuestionPeek.FOCUSED, session.peek)
            val old = session.current!!.question!!
            session.replaceCrop(old.copy(crop = NormalizedRect(0.0, 0.0, 1.0, 1.0)))
            assertEquals("a", session.current!!.question!!.id)
            assertEquals("C", session.selectedChoice)
            assertEquals(0, saves)
            assertTrue(session.review!!.overlays.all { it.result == QuestionResult.UNKNOWN })
        } finally { work.cancel() }
    }

    @Test fun deletingLastQuestionSelectsPreviousAndDeletingAllLeavesEmptySession() = runBlocking {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val deleted = mutableListOf<String>()
        val opened = mutableListOf<String?>()
        try {
            val session = QuestionSession(set(), File("source"), scope = work,
                initialProgress = QuestionProgress(lastQuestionId = "b", choices = mapOf("a" to "C", "b" to "D")),
                beforeTransition = {}, onDelete = { deleted += it }, onNavigate = { opened += it?.id })
            session.deleteCurrent()
            yield()
            assertEquals("a", session.current!!.question!!.id)
            assertEquals("C", session.selectedChoice)
            session.cyclePeek()
            assertEquals(QuestionPeek.FADED, session.peek)
            session.cyclePeek()
            assertEquals(listOf("a"), opened)
            session.deleteCurrent()
            yield()
            assertEquals(listOf("b", "a"), deleted)
            assertEquals(listOf("a", null), opened)
            assertEquals(0, session.count)
            assertNull(session.current)
        } finally { work.cancel() }
    }

    @Test fun failedNotebookWriteKeepsQuestionOpenAndRetryClearsError() = runBlocking {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        var fail = true
        try {
            val session = QuestionSession(set(), File("source"), scope = work,
                beforeTransition = { check(!fail) })
            session.next()
            yield()
            assertEquals(0, session.index)
            assertNotNull(session.progressError)
            fail = false
            session.retryProgress()
            yield()
            assertNull(session.progressError)
            session.next()
            yield()
            assertEquals(1, session.index)
        } finally { work.cancel() }
    }
}
