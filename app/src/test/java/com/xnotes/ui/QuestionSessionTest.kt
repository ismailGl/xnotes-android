package com.xnotes.ui

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question
import com.xnotes.platform.QuestionSetRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class QuestionSessionTest {
    @Test fun sidebarJumpKeepsSourceDocumentAndUsesStableId() = kotlinx.coroutines.runBlocking {
        val pdf = File("same.pdf")
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
        val ids = listOf("q3", "q1", "q2")
        val set = QuestionSetRepository.LoadedSet("set", "Title", ids.map {
            QuestionSetRepository.Entry(Question(it, 0, NormalizedRect(0.0,0.0,1.0,1.0)))
        })
        val visited = mutableListOf<String?>()
        val session = QuestionSession(set, pdf, scope = scope, beforeTransition = {},
            onNavigate = { visited += it?.id })
        try {
            session.sidebarVisible = true
            session.jumpTo("q2")
            kotlinx.coroutines.withTimeout(3000) { while (session.current?.question?.id != "q2") kotlinx.coroutines.yield() }
            assertSame(pdf, session.sourcePdf)
            assertEquals(listOf("q2"), visited)
            assertTrue(session.sidebarVisible)
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }
    @Test fun directChoicesPersistAndPeekReturnsToSameQuestion() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())
        var saved = com.xnotes.platform.QuestionProgress()
        val store = object : com.xnotes.platform.QuestionProgressStore {
            override suspend fun load() = saved
            override suspend fun save(progress: com.xnotes.platform.QuestionProgress) { saved=progress }
        }
        val q = QuestionSetRepository.Entry(Question("a",0,NormalizedRect(0.0,0.0,1.0,1.0)))
        val set = QuestionSetRepository.LoadedSet("set","Title",listOf(q))
        val session = QuestionSession(set,File("source.pdf"),scope=scope,progressStore=store)
        session.setAnswerOptions(com.xnotes.platform.QuestionAnswerOptions(optionCount=3))
        assertEquals(listOf("A","B","C"),session.answerOptions.choices)
        session.selectChoice("B")
        kotlinx.coroutines.yield()
        assertEquals("B",session.selectedChoice)
        session.cyclePeek(); session.cyclePeek()
        assertEquals(q,session.current)
        assertEquals(QuestionPeek.FOCUSED,session.peek)
        assertEquals("B",QuestionSession(set,File("source.pdf"),initialProgress=saved).selectedChoice)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }
    @Test fun navigationIsBoundedAndCanPassInvalidEntries() {
        val good = QuestionSetRepository.Entry(Question("a", 0, NormalizedRect(0.0, 0.0, 1.0, 1.0)))
        val bad = QuestionSetRepository.Entry(null, "Invalid crop")
        val session = QuestionSession(QuestionSetRepository.LoadedSet("set", "Title", listOf(good, bad, good)), File("source.pdf"))
        assertFalse(session.canPrevious)
        session.previous()
        assertEquals(0, session.index)
        session.next()
        assertEquals(bad, session.current)
        session.next()
        assertEquals(good, session.current)
        assertFalse(session.canNext)
        session.next()
        assertEquals(2, session.index)
        session.previous()
        assertEquals(1, session.index)
    }

    @Test fun emptySetHasNoActiveQuestionOrNavigation() {
        val session = QuestionSession(QuestionSetRepository.LoadedSet("set", "Title", emptyList()), File("source.pdf"))
        session.next()
        session.previous()
        assertEquals(0, session.index)
        assertNull(session.current)
        assertFalse(session.canNext)
        assertFalse(session.canPrevious)
    }
}
