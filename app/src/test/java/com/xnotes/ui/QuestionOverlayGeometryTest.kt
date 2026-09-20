package com.xnotes.ui

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question
import org.junit.Assert.*
import org.junit.Test

class QuestionOverlayGeometryTest {
    @Test fun hitReturnsStableIdAndRespectsPageAndCrop() {
        val a = Question("stable-a", 0, NormalizedRect(.1,.1,.4,.4))
        val b = Question("stable-b", 1, NormalizedRect(.5,.5,.9,.9))
        assertEquals("stable-b", QuestionOverlayGeometry.hit(listOf(a,b), 1, .6,.6)?.id)
        assertNull(QuestionOverlayGeometry.hit(listOf(a,b), 0, .6,.6))
        assertEquals(0, QuestionOverlayGeometry.corner(a.crop,.1,.1,.01,.01))
    }
    @Test fun disabledOverlayAndOrdinaryPenDoNotCaptureEditorGestures() {
        assertFalse(QuestionOverlayGeometry.capturesDown(false, false, true, false))
        assertFalse(QuestionOverlayGeometry.capturesDown(true, false, false, false))
        assertFalse(QuestionOverlayGeometry.capturesDown(true, false, true, true))
        assertTrue(QuestionOverlayGeometry.capturesDown(true, false, true, false))
        assertTrue(QuestionOverlayGeometry.capturesDown(true, true, true, true))
    }
}
