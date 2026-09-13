package com.xnotes.ui

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question
import com.xnotes.platform.QuestionSetRepository
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class QuestionSessionTest {
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
