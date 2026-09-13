package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QuestionCropTest {
    private fun state(rotation: Int = 0): CanvasState = CanvasState(
        Document(pages = mutableListOf(Page(600.0, 800.0, pdfPage = 7), Page(600.0, 800.0, pdfPage = 2)), pdfFile = File("source.pdf")),
        FakeSurfaceFactory(), Palette.forAppearance("dark", Rgba(0, 230, 118)),
    ).apply { rotationDeg = rotation; viewportW = 1200; viewportH = 900; relayout() }

    @Test fun cropUsesSourceIndexAndSurvivesRotationZoomAndMargins() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val st = state(rotation)
            st.document.pages[0].margins = PageMargins(left = 0.1, top = 0.2)
            st.relayout()
            st.zoom = 2.5
            st.scrollX = 43.0
            st.scrollY = 71.0
            val content = st.fromPageSpaceRect(0, Rect(60.0, 160.0, 240.0, 320.0))
            val result = QuestionCrop.fromSelection(st, content)
            assertEquals(7, result.sourcePageIndex)
            assertEquals(NormalizedRect(0.1, 0.2, 0.5, 0.6), result.crop)
        }
    }

    @Test fun clipsExtraPaperToPdfBounds() {
        val st = state()
        st.document.pages[0].margins = PageMargins(left = 0.1)
        st.relayout()
        val result = QuestionCrop.fromSelection(st, st.fromPageSpaceRect(0, Rect(-30.0, 80.0, 330.0, 320.0)))
        assertEquals(NormalizedRect(0.0, 0.1, 0.5, 0.5), result.crop)
    }

    @Test fun rejectsCrossPageGapBlankAndTinySelections() {
        val st = state()
        fun rejected(rect: Rect) {
            assertThrows(IllegalArgumentException::class.java) { QuestionCrop.fromSelection(st, rect) }
        }
        rejected(st.pageRects[0].union(st.pageRects[1]))
        rejected(st.pageRects[0].outset(1.0))
        rejected(st.fromPageSpaceRect(0, Rect(20.0, 20.0, 2.0, 40.0)))
        rejected(Rect(Double.NaN, 0.0, 20.0, 20.0))
        st.document.pages[0].pdfPage = null
        rejected(st.fromPageSpaceRect(0, Rect(20.0, 20.0, 40.0, 40.0)))
    }
}
