package com.xnotes.core.geometry

import org.junit.Assert.*
import org.junit.Test

class ZoomPanTransformTest {
    @Test fun tallAndWideQuestionsFitCompletelyAndAreCentered() {
        for ((w, h) in listOf(100.0 to 2000.0, 2000.0 to 100.0, 600.0 to 800.0)) {
            val v = ZoomPanTransform(w, h, 1200.0, 700.0)
            assertTrue(w * v.scale <= 1200.0 + 1e-9)
            assertTrue(h * v.scale <= 700.0 + 1e-9)
            assertEquals(600.0, v.left + w * v.scale / 2, 1e-9)
            assertEquals(350.0, v.top + h * v.scale / 2, 1e-9)
            assertEquals(Rect(0.0, 0.0, w, h), v.visibleRect())
        }
    }

    @Test fun pinchKeepsItsAnchorStationary() {
        val fit = ZoomPanTransform(1000.0, 1000.0, 1000.0, 1000.0)
        val anchor = Pt(350.0, 420.0)
        val zoomed = fit.gesture(anchor, Pt.ZERO, 2.0)
        assertEquals(anchor.x, zoomed.left + anchor.x * zoomed.scale, 1e-9)
        assertEquals(anchor.y, zoomed.top + anchor.y * zoomed.scale, 1e-9)
    }

    @Test fun zoomAndPanAreClampedAndResetRestoresFit() {
        val fit = ZoomPanTransform(1000.0, 1000.0, 1000.0, 1000.0)
        val zoomed = fit.gesture(Pt(500.0, 500.0), Pt(1e6, -1e6), 100.0)
        assertEquals(5.0, zoomed.zoom, 0.0)
        assertEquals(0.0, zoomed.left, 0.0)
        assertEquals(1000.0, zoomed.top + 1000.0 * zoomed.scale, 0.0)
        assertEquals(fit, zoomed.reset())
        assertEquals(fit, zoomed.gesture(Pt.ZERO, Pt(200.0, 200.0), 0.01))
    }

    @Test fun axesThatStillFitCannotPan() {
        val fit = ZoomPanTransform(100.0, 2000.0, 1200.0, 700.0)
        assertEquals(fit, fit.gesture(Pt.ZERO, Pt(400.0, 400.0), 1.0))
        val zoomed = fit.gesture(Pt(600.0, 350.0), Pt(400.0, 200.0), 3.0)
        assertEquals(0.0, zoomed.panX, 0.0)
        assertEquals(200.0, zoomed.panY, 0.0)
    }

    @Test fun newViewportRecomputesFitWithoutOldPanOrZoom() {
        val landscape = ZoomPanTransform(600.0, 800.0, 1200.0, 700.0)
        val portrait = ZoomPanTransform(600.0, 800.0, 700.0, 1200.0)
        assertNotEquals(landscape.scale, portrait.scale, 1e-9)
        assertEquals(1.0, portrait.zoom, 0.0)
        assertEquals(0.0, portrait.panX, 0.0)
        assertEquals(0.0, portrait.panY, 0.0)
    }
}
