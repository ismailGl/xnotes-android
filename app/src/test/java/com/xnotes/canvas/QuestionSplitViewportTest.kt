package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.Palette
import com.xnotes.platform.QuestionSetRepository
import com.xnotes.ui.QuestionSession
import com.xnotes.ui.QuestionWorkspaceRoute
import com.xnotes.ui.QuestionWorkspaceAction
import com.xnotes.ui.SourceQuestion
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class QuestionSplitViewportTest {
    private fun view(doc: Document) = CanvasState(doc, FakeSurfaceFactory(),
        Palette.forAppearance("dark", Rgba(0,230,118)))

    @Test fun sameSourceCanHaveIndependentViewportAndQuestionPosition() {
        val source = File("same.pdf")
        val a = view(Document.blank(3)); val b = view(Document.blank(3))
        a.zoom = 2.0; a.scrollX = 120.0; a.scrollY = 90.0
        b.zoom = 1.2; b.scrollX = 10.0; b.scrollY = 20.0
        val set = QuestionSetRepository.LoadedSet("set", "Title", listOf("a","b").map {
            QuestionSetRepository.Entry(Question(it, 0, NormalizedRect(0.0,0.0,1.0,1.0)))
        })
        val first = QuestionSession(set, source)
        val second = QuestionSession(set, source)
        second.jumpTo("b")
        assertSame(source, first.sourcePdf)
        assertSame(source, second.sourcePdf)
        assertEquals("a", first.current?.question?.id)
        assertEquals("b", second.current?.question?.id)
        assertEquals(2.0, a.zoom, 0.0)
        assertEquals(1.2, b.zoom, 0.0)
        assertEquals(90.0, a.scrollY, 0.0)
        assertEquals(20.0, b.scrollY, 0.0)
    }

    @Test fun differentSourcesCanKeepSeparateDocumentAndViewport() {
        val a = view(Document.blank(2)); val b = view(Document.blank(4))
        a.zoom = 1.8; b.zoom = 0.8
        assertNotSame(a.document, b.document)
        assertEquals(2, a.document.pages.size)
        assertEquals(4, b.document.pages.size)
        assertEquals(1.8, a.zoom, 0.0)
        assertEquals(0.8, b.zoom, 0.0)
    }

    @Test fun differentDocumentsNavigateIndependentlyAndClosingRightKeepsLeftPage() {
        val left = view(Document.blank(3))
        val right = view(Document.blank(5))
        listOf(left, right).forEach { it.viewportW = 500; it.viewportH = 400; it.relayout(); it.fitWidth() }
        left.goToPage(1)
        right.goToPage(4)
        assertEquals(1, left.currentPageIndex())
        assertEquals(4, right.currentPageIndex())
        left.goToPage(2)
        assertEquals(4, right.currentPageIndex())
        val leftDoc = left.document
        // The shell removing a pane does not replace or reset the surviving CanvasState.
        left.viewportW = 1000
        left.relayout()
        left.reflowFitWidthForResize()
        assertSame(leftDoc, left.document)
        assertEquals(2, left.currentPageIndex())
        assertEquals(left.fitWidthZoom(), left.zoom, 1e-6)
    }

    @Test fun sameSourceSeparatePageNavigationAndQuestionModeSession() {
        val source = File("same.pdf")
        val left = view(Document.blank(4)); val right = view(Document.blank(4))
        listOf(left, right).forEach { it.viewportW = 500; it.viewportH = 400; it.relayout(); it.fitWidth() }
        left.goToPage(0)
        right.goToPage(3)
        val set = QuestionSetRepository.LoadedSet("set", "Title", listOf("a", "b").map {
            QuestionSetRepository.Entry(Question(it, 0, NormalizedRect(0.0, 0.0, 1.0, 1.0)))
        })
        val questionOnLeft = QuestionSession(set, source)
        questionOnLeft.jumpTo("b")
        assertEquals(0, left.currentPageIndex())
        assertEquals(3, right.currentPageIndex())
        assertEquals("b", questionOnLeft.current?.question?.id)
        right.goToPage(2)
        assertEquals("b", questionOnLeft.current?.question?.id)
        assertEquals(0, left.currentPageIndex())
        val questionOnRight = QuestionSession(set, source)
        questionOnRight.jumpTo("b")
        questionOnRight.sidebarVisible = true
        assertEquals(0, left.currentPageIndex())
        assertEquals(2, right.currentPageIndex())
        assertEquals("b", questionOnRight.current?.question?.id)
        assertFalse(questionOnLeft.sidebarVisible)
    }

    @Test fun sourceQuestionSelectionKeepsRightPageAndCompactActionsChangeLeftSession() {
        val left = view(Document.blank(2)); val right = view(Document.blank(5))
        listOf(left, right).forEach { it.viewportW = 500; it.viewportH = 400; it.relayout(); it.fitWidth() }
        right.goToPage(3)
        val set = QuestionSetRepository.LoadedSet("source-set", "Source", listOf("q1", "q2").map {
            QuestionSetRepository.Entry(Question(it, 0, NormalizedRect(0.0, 0.0, 1.0, 1.0)))
        })
        assertEquals(QuestionWorkspaceAction.OPEN_SOURCE,
            QuestionWorkspaceRoute.action(SourceQuestion("B", "source-set", "q1"),
                listOf("q1", "q2"), "A", "other-set"))
        val questionWorkspace = QuestionSession(set, File("B.pdf"))
        questionWorkspace.selectChoice("C")
        questionWorkspace.next()
        assertEquals("q2", questionWorkspace.current?.question?.id)
        assertEquals("C", questionWorkspace.choiceFor("q1"))
        assertEquals(3, right.currentPageIndex())
        assertEquals(0, left.currentPageIndex())
    }

    @Test fun pdfFitRecomputesForSplitCloseReopenAndRotation() {
        val pane = view(Document.blank(2))
        pane.viewportW = 1000; pane.viewportH = 600; pane.relayout(); pane.fitWidth()
        for ((w, h) in listOf(350 to 600, 1000 to 600, 350 to 600, 600 to 1000)) {
            val previousCenter = pane.viewportToContent(
                com.xnotes.core.geometry.Pt(pane.viewportW / 2.0, pane.viewportH / 2.0)).y
            pane.viewportW = w; pane.viewportH = h; pane.relayout()
            pane.reflowFitWidthForResize(previousCenter)
            assertEquals(pane.fitWidthZoom(), pane.zoom, 1e-6)
            assertEquals(w, pane.viewportW)
            assertEquals(h, pane.viewportH)
        }
    }
}
