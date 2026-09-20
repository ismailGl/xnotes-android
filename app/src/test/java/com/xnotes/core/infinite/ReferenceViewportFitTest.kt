package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ReferenceViewportFitTest {
    private val crop = Rect(200.0, 100.0, 400.0, 600.0)
    private fun viewport(w: Int, h: Int) = CanvasViewport().apply { widthPx = w; heightPx = h }
    private fun assertCentered(v: CanvasViewport, rect: Rect) {
        val visible = v.contentToViewport(rect)
        assertEquals(v.widthPx / 2.0, visible.centerX, 1e-6)
        assertEquals(v.heightPx / 2.0, visible.centerY, 1e-6)
    }

    @Test fun questionUsesItsOwnSplitPaneAndRefitsAfterClosingRight() {
        val left = viewport(500, 700)
        val right = viewport(500, 700)
        val fit = ReferenceViewportFit()
        fit.fit(left, crop)
        right.zoom = 1.7; right.scrollX = 23.0
        assertCentered(left, crop)
        left.widthPx = 1000
        assertTrue(fit.refitIfStillAutomatic(left, crop))
        assertCentered(left, crop)
        assertEquals(1.7, right.zoom, 0.0)
        assertEquals(23.0, right.scrollX, 0.0)
    }

    @Test fun enteringQuestionModeAfterOldSplitWidthGetsMeasuredFullWidth() {
        val v = viewport(500, 700)
        val fit = ReferenceViewportFit()
        fit.fit(v, crop)
        v.widthPx = 1000
        assertTrue(fit.refitIfStillAutomatic(v, crop))
        assertCentered(v, crop)
    }

    @Test fun sidebarAndDividerResizeUseRemainingCanvasBounds() {
        val v = viewport(620, 700)
        val fit = ReferenceViewportFit()
        fit.fit(v, crop)
        for (width in listOf(380, 540, 900, 620)) {
            v.widthPx = width
            assertTrue(fit.refitIfStillAutomatic(v, crop))
            assertCentered(v, crop)
        }
    }

    @Test fun navigationFitsNewQuestionButManualPanIsPreservedOnResize() {
        val v = viewport(500, 700)
        val fit = ReferenceViewportFit()
        fit.fit(v, crop)
        val next = Rect(900.0, 300.0, 300.0, 200.0)
        fit.fit(v, next)
        assertCentered(v, next)
        v.panByViewport(50.0, 20.0)
        val x = v.scrollX
        v.widthPx = 700
        assertFalse(fit.refitIfStillAutomatic(v, next))
        assertEquals(x, v.scrollX, 0.0)
    }

    @Test fun pendingFitUsesFirstMeasuredViewport() {
        val v = viewport(0, 0)
        val fit = ReferenceViewportFit()
        fit.fit(v, crop)
        v.widthPx = 1000; v.heightPx = 700
        assertTrue(fit.refitIfStillAutomatic(v, crop))
        assertCentered(v, crop)
    }

    @Test fun reopeningPaneAndRotatingUseFreshMeasuredBounds() {
        val v = viewport(1000, 600)
        val fit = ReferenceViewportFit()
        fit.fit(v, crop)
        for ((w, h) in listOf(300 to 600, 1000 to 600, 400 to 900, 900 to 400)) {
            v.widthPx = w; v.heightPx = h
            assertTrue(fit.refitIfStillAutomatic(v, crop))
            assertCentered(v, crop)
        }
    }
}
