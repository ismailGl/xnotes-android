package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test

class QuestionEditorViewportTest {
    @Test fun focusedCropOnSecondPageUsesNormalPageCoordinatesAtEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val doc = Document(mutableListOf(Page(600.0, 800.0), Page(600.0, 800.0, pdfPage = 7)))
            val state = CanvasState(doc, FakeSurfaceFactory(), Palette.forAppearance("dark", Rgba(0, 230, 118)))
            state.viewportW = 1200; state.viewportH = 900
            state.rotationDeg = rotation
            state.relayout()
            state.focusedPage = 1; state.cropPageIndex = 1
            state.pageCrop = Rect(60.0, 160.0, 240.0, 320.0)
            state.fitPage()
            val focused = state.fromPageSpaceRect(1, state.pageCrop!!)
            val center = state.contentToViewport(Pt(focused.centerX, focused.centerY))
            assertEquals(600.0, center.x, .001)
            assertEquals(450.0, center.y, .001)
            state.scrollBy(100000.0, 100000.0)
            val afterPan = state.contentToViewport(Pt(focused.centerX, focused.centerY))
            assertEquals(center.x, afterPan.x, .001)
            assertEquals(center.y, afterPan.y, .001)
            assertSame(doc, state.document)
            assertEquals(1..1, state.drawablePageRange())
            assertEquals(1, state.currentPageIndex())
            val point = Pt(100.0, 200.0)
            val content = state.fromPageSpace(1, point)
            assertEquals(1, state.pageIndexAtContent(content))
            assertEquals(point, state.toPageSpace(1, content))
            assertNull(state.pageIndexAtContent(state.fromPageSpace(1, Pt(10.0, 10.0))))
            val page = doc.pages[1]
            // Peek and a crop correction only change the view; no replacement pages or ink transforms.
            state.pageCrop = null
            state.fitPage()
            assertSame(page, doc.pages[1])
            assertEquals(1, state.pageIndexAtContent(state.fromPageSpace(1, Pt(10.0, 10.0))))
            state.pageCrop = Rect(0.0, 0.0, 400.0, 500.0)
            state.fitWidth()
            assertEquals(point, state.toPageSpace(1, state.fromPageSpace(1, point)))
        }
    }
}
