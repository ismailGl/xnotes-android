package com.xnotes.ui

import com.xnotes.core.model.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotebookQuestionStoreTest {
    @Test fun editsRemainInNotebookAndSaveDelegatesToNotebookOwner() = runBlocking {
        val page = Page(1000.0, 2000.0, pdfPage = 3)
        val notebook = Document(mutableListOf(Page(1000.0, 2000.0), page))
        val question = Question("q", 3, NormalizedRect(0.1, 0.2, 0.7, 0.8))
        var saved: Document? = null
        val store = NotebookQuestionStore(notebook, listOf(question)) { saved = notebook.snapshot() }
        val view = store.load("q")
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(200.0, 500.0, 0.7)))
        view.pages.single().items.add(stroke)
        assertSame(stroke, notebook.pages[1].items.single())
        store.save("q", view.snapshot())
        assertEquals(2, saved!!.pages.size)
        assertEquals(1, saved!!.pages[1].items.size)
        assertSame(page, store.load("q").pages.single())
        page.items.remove(stroke)
        assertTrue(view.pages.single().items.isEmpty())
    }
}
