package com.xnotes.core

import com.xnotes.core.history.*
import com.xnotes.core.model.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test

class NotebookQuestionPageTest {
    private fun ink() = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(500.0, 600.0, 0.5)))
    private val question = Question("q", 7, NormalizedRect(0.2, 0.3, 0.8, 0.7))

    @Test fun sharesOriginalPageAndExistingItemsAndNormalHistory() {
        val page = Page(2000.0, 3000.0, pdfPage = 7)
        val old = ink()
        page.items.add(old)
        val notebook = Document(mutableListOf(Page(100.0, 100.0), page))
        val binding = NotebookQuestionPage(notebook, question)
        assertSame(page, binding.viewDocument.pages.single())
        assertSame(old, binding.viewDocument.pages.single().items.single())
        val history = History()
        val added = ink()
        binding.page.items.add(added)
        history.push(AddItem(binding.page, added))
        assertEquals(2, notebook.pages[1].items.size)
        assertTrue(binding.canApply(history.nextUndo))
        history.undo()
        assertEquals(listOf(old), page.items)
        history.redo()
        assertSame(added, page.items.last())
        val erase = EraseItems(listOf(binding.page to old))
        erase.redo(); history.push(erase)
        assertFalse(page.items.any { it === old })
        history.undo()
        assertTrue(page.items.any { it === old })
        assertEquals(400.0, binding.crop.left, 0.0)
        assertEquals(900.0, binding.crop.top, 0.0)
    }

    @Test fun historyGuardRejectsOtherPagesStructuralAndMixedCommands() {
        val page = Page(2000.0, 3000.0, pdfPage = 7)
        val other = Page(2000.0, 3000.0, pdfPage = 2)
        val doc = Document(mutableListOf(other, page))
        val binding = NotebookQuestionPage(doc, question)
        assertFalse(binding.canApply(AddItem(other, ink())))
        assertFalse(binding.canApply(DeletePage(doc, page, 1)))
        assertFalse(binding.canApply(CompositeCommand(listOf(AddItem(page, ink()), AddItem(other, ink())))))
        val stroke = ink().also { page.items.add(it) }
        assertTrue(binding.canApply(MoveItems(listOf(stroke), 10.0, 20.0)))
    }

    @Test fun missingOrAmbiguousPdfPageIsRejectedRatherThanEditingAnotherPage() {
        for (doc in listOf(Document.blank(), Document(mutableListOf(
                Page(100.0, 100.0, pdfPage = 7), Page(100.0, 100.0, pdfPage = 7))))) {
            try { NotebookQuestionPage(doc, question); fail("Ambiguous source accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
