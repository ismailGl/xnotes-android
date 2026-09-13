package com.xnotes.platform

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.ZoomPanTransform
import com.xnotes.core.model.NormalizedRect
import org.junit.Assert.*
import org.junit.Test

class QuestionRenderPlanTest {
    private val crop = NormalizedRect(0.25, 0.125, 0.75, 0.625)
    private fun fitted() = ZoomPanTransform(300.0, 400.0, 1200.0, 800.0)

    @Test fun fittedRegionContainsEntireCropAtPhysicalDisplayResolution() {
        val p = QuestionRenderPlan.create(7, 600, 800, crop, fitted())
        assertEquals(7, p.pageIndex)
        assertEquals(1200, p.fullWidth)
        assertEquals(1600, p.fullHeight)
        assertEquals(300, p.left)
        assertEquals(200, p.top)
        assertEquals(600, p.width)
        assertEquals(800, p.height)
        assertEquals(0.0, p.contentRect.left, 1e-9)
        assertEquals(0.0, p.contentRect.top, 1e-9)
        assertEquals(300.0, p.contentRect.w, 1e-9)
        assertEquals(400.0, p.contentRect.h, 1e-9)
    }

    @Test fun zoomIncreasesSourceResolutionWithoutAllocatingTheWholeEnlargedCrop() {
        val fit = fitted()
        val zoomed = fit.gesture(Pt(600.0, 400.0), Pt.ZERO, 5.0)
        val base = QuestionRenderPlan.create(0, 600, 800, crop, fit)
        val sharp = QuestionRenderPlan.create(0, 600, 800, crop, zoomed)
        assertEquals(base.fullWidth * 5, sharp.fullWidth)
        assertEquals(1200, sharp.width)
        assertEquals(800, sharp.height)
        assertTrue(sharp.contentRect.w < base.contentRect.w)
        assertTrue(sharp.contentRect.h < base.contentRect.h)
    }

    @Test fun panningMovesSourceRegionAndOutwardRoundingCoversVisibleContent() {
        val zoomed = fitted().gesture(Pt(600.0, 400.0), Pt.ZERO, 3.17)
        val panned = zoomed.gesture(Pt(600.0, 400.0), Pt(200.0, -100.0), 1.0)
        val before = QuestionRenderPlan.create(0, 600, 800, crop, zoomed)
        val after = QuestionRenderPlan.create(0, 600, 800, crop, panned)
        assertTrue(after.left < before.left)
        assertTrue(after.top > before.top)
        val visible = panned.visibleRect()
        assertTrue(after.contentRect.left <= visible.left + 1e-9)
        assertTrue(after.contentRect.top <= visible.top + 1e-9)
        assertTrue(after.contentRect.right >= visible.right - 1e-9)
        assertTrue(after.contentRect.bottom >= visible.bottom - 1e-9)
    }

    @Test fun tallQuestionsFitAndLargeWindowsHaveBoundedBitmaps() {
        val tallCrop = NormalizedRect(0.45, 0.0, 0.55, 1.0)
        val tall = ZoomPanTransform(60.0, 800.0, 2400.0, 1400.0)
        val p = QuestionRenderPlan.create(0, 600, 800, tallCrop, tall)
        assertTrue(p.width <= 107)
        assertEquals(1400, p.height)
        val big = QuestionRenderPlan.create(0, 600, 800, crop, ZoomPanTransform(300.0, 400.0, 10_000.0, 10_000.0))
        assertTrue(big.width <= 4096 && big.height <= 4096)
        assertTrue(big.width.toLong() * big.height < 8_020_000)
    }

    @Test fun rejectsInvalidPageAndUnsafeScale() {
        assertThrows(IllegalArgumentException::class.java) { QuestionRenderPlan.create(-1, 600, 800, crop, fitted()) }
        assertThrows(IllegalArgumentException::class.java) { QuestionRenderPlan.create(0, 0, 800, crop, fitted()) }
        assertThrows(IllegalArgumentException::class.java) {
            QuestionRenderPlan.create(0, 600, 800, NormalizedRect(0.0, 0.0, 1e-12, 1e-12),
                ZoomPanTransform(6e-10, 8e-10, 1200.0, 800.0))
        }
    }
}
