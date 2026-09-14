package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.ZoomPanTransform
import com.xnotes.core.model.Document
import com.xnotes.core.model.Rgba
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test

class QuestionInkTransformTest {
    @Test fun originalPageCropOffsetAndDpiStayAlignedAcrossZoomPanAndDeviceRotation() {
        val doc = Document.blankPixels(width = 2400.0, height = 3200.0)
        val state = CanvasState(doc, FakeSurfaceFactory(), Palette.forAppearance("dark", Rgba(0, 230, 118)))
        state.pageCrop = com.xnotes.core.geometry.Rect(480.0, 800.0, 1200.0, 800.0)
        val fit = ZoomPanTransform(300.0, 200.0, 1200.0, 400.0)
        val zoom = fit.pinch(Pt(600.0, 200.0), Pt(550.0, 150.0), 4.0)
        for (v in listOf(fit, zoom, zoom.reset(), ZoomPanTransform(300.0, 200.0, 500.0, 900.0))) {
            state.viewportW = v.viewportWidth.toInt(); state.viewportH = v.viewportHeight.toInt()
            state.relayout(); state.applyCropViewport(v)
            val original = Pt(880.0, 1000.0)
            val screen = state.contentToViewport(state.fromPageSpace(0, original))
            assertEquals(v.left + 100.0 * v.scale, screen.x, 1e-8)
            assertEquals(v.top + 50.0 * v.scale, screen.y, 1e-8)
            val mapped = state.toPageSpace(0, state.viewportToContent(screen))
            assertEquals(original.x, mapped.x, 1e-8)
            assertEquals(original.y, mapped.y, 1e-8)
            assertNull(state.pageIndexAtContent(state.fromPageSpace(0, Pt(10.0, 10.0))))
            assertEquals(0, state.pageIndexAtContent(state.fromPageSpace(0, original)))
        }
    }
    @Test fun inkAndPdfCoordinatesAgreeAtFitZoomPanResetAndResize() {
        val state = CanvasState(Document.blankPixels(width = 600.0, height = 900.0),
            FakeSurfaceFactory(), Palette.forAppearance("dark", Rgba(0, 230, 118)))
        state.relayout()
        val fitted = ZoomPanTransform(600.0, 900.0, 1200.0, 400.0)
        val zoomed = fitted.gesture(Pt(540.0, 170.0), Pt(-120.0, 35.0), 4.0)
        for (transform in listOf(fitted, zoomed, zoomed.reset(), ZoomPanTransform(600.0, 900.0, 500.0, 1100.0))) {
            state.viewportW = transform.viewportWidth.toInt()
            state.viewportH = transform.viewportHeight.toInt()
            state.relayout()
            state.applyCropViewport(transform)
            for (point in listOf(Pt(0.0, 0.0), Pt(234.0, 678.0), Pt(600.0, 900.0))) {
                val screen = state.contentToViewport(state.fromPageSpace(0, point))
                assertEquals(transform.left + point.x * transform.scale, screen.x, 1e-8)
                assertEquals(transform.top + point.y * transform.scale, screen.y, 1e-8)
                val ink = state.toPageSpace(0, state.viewportToContent(screen))
                assertEquals(point.x, ink.x, 1e-8)
                assertEquals(point.y, ink.y, 1e-8)
            }
        }
    }
}
