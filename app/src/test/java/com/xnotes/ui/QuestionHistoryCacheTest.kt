package com.xnotes.ui

import com.xnotes.core.history.AddItem
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.snapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.platform.AnswerStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QuestionHistoryCacheTest {
    private class Surface(override val document: Document, override val history: History, val change: () -> Unit) : AnswerSurface {
        override var inputEnabled = true
        override fun finishInput() = Unit
        override fun snapshot() = document.snapshot()
        var disposed = false
        override fun dispose() { disposed = true }
        fun draw() {
            val page = document.pages.single()
            val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(10.0, 20.0, 0.6)))
            page.items.add(stroke)
            history.push(AddItem(page, stroke))
            change()
        }
    }
    @Test fun reentryRestoresDocumentIdentityAndUndoRedoWithoutKeepingSurface() = runBlocking {
        val memory = QuestionHistoryCache()
        var reads = 0
        val store = object : AnswerStore {
            override suspend fun load(answerId: String): Document { reads++; return Document.blank() }
            override suspend fun save(answerId: String, document: Document) = Unit
        }
        fun host() = QuestionAnswerSession(store, { doc, history, change -> Surface(doc, history ?: History(), change) },
            CoroutineScope(coroutineContext + SupervisorJob()), memory = memory, memoryPrefix = "set/answers/")
        suspend fun idle(session: QuestionAnswerSession) = withTimeout(3000) { while (session.busy) yield() }
        val first = host()
        first.switchTo("a"); idle(first)
        val old = first.surface as Surface
        old.draw()
        old.history.undo(); old.change()
        first.close {}; idle(first)
        assertTrue(old.disposed)
        assertNull(first.surface)
        val second = host()
        second.switchTo("a"); idle(second)
        val restored = second.surface as Surface
        assertNotSame(old, restored)
        assertSame(old.document, restored.document)
        assertSame(old.history, restored.history)
        assertTrue(restored.history.canRedo)
        restored.history.redo(); restored.change()
        assertEquals(1, restored.document.pages.single().items.size)
        restored.history.undo(); restored.change()
        assertTrue(restored.document.pages.single().items.isEmpty())
        assertEquals(1, reads)
        second.close {}; idle(second)
    }

    @Test fun cacheSeparatesLayersAndSetsAndEvictsOldestEntries() {
        val cache = QuestionHistoryCache(2)
        val answer = Document.blank()
        val annotation = Document.blank()
        cache.retain("set/answers/a", answer, History())
        cache.retain("set/annotations/a", annotation, History())
        assertSame(annotation, cache.take("set/annotations/a")!!.document)
        cache.retain("set/annotations/a", annotation, History())
        cache.retain("other/answers/a", Document.blank(), History())
        assertEquals(2, cache.size)
        assertNull(cache.take("set/answers/a"))
        assertNotNull(cache.take("set/annotations/a"))
    }
}
