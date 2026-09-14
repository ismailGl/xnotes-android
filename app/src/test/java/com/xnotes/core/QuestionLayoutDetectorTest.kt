package com.xnotes.core

import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestionLayoutDetectorTest {
    @Test fun containmentWarnsWithoutSilentlySkippingLargerCrops() {
        val small=NormalizedRect(0.2,0.2,0.5,0.4)
        val large=NormalizedRect(0.0,0.1,0.6,0.5)
        assertTrue(QuestionOverlap.possibleDuplicate(small,large))
        assertFalse(QuestionOverlap.duplicate(small,large))
        assertTrue(QuestionOverlap.duplicate(small,small))
        assertFalse(QuestionOverlap.possibleDuplicate(small,NormalizedRect(0.6,0.6,0.9,0.9)))
    }
    private fun line(text: String, x: Double, y: Double, right: Double = 0.9) =
        QuestionLayoutDetector.TextRun(text, NormalizedRect(x,y,right,y+0.02))
    private fun layout() = QuestionLayoutDetector.Layout(1000,1000,BooleanArray(1_000_000))
    @Test fun singleColumnIncludesDiagramAndOpenEndedSpaceWithoutOptions() {
        val image = layout()
        for(y in 240..370) for(x in 550..850) image.ink[y*1000+x] = true
        val detected = QuestionLayoutDetector.detect(3,listOf(line("1. Açıklayınız",0.08,0.1),line("2) Çözünüz",0.08,0.5)),image)
        assertEquals(2,detected.size)
        assertEquals(3,detected[0].sourcePageIndex)
        assertTrue(detected[0].crop.right >= 0.85)
        assertTrue(detected[0].crop.bottom > 0.37)
        assertTrue(detected[0].crop.bottom < 0.5)
        assertTrue(detected.last().reasons.any { it.contains("continuation") })
    }
    @Test fun unevenColumnsHaveIndependentNumberingAndGeometricGutter() {
        val detected = QuestionLayoutDetector.detect(0,listOf(
            line("Soru 3 Açıklama",0.04,0.10,0.58), line("1. Yeni test",0.04,0.5,0.58),
            line("12) Soru",0.72,0.1,0.95), line("13. Soru",0.72,0.55,0.95)),layout())
        assertEquals(4,detected.size)
        val gutter = detected.first().crop.right
        assertTrue(gutter in 0.59..0.71)
        assertEquals(gutter,detected[2].crop.left,0.0001)
        assertEquals(0.094,detected[2].crop.top,0.001)
    }
    @Test fun choicesAndFooterAreNotQuestionAnchorsAndEmptyTextStaysEmpty() {
        assertTrue(QuestionLayoutDetector.detect(0,listOf(line("A) Choice",0.1,0.2),line("32",0.1,0.97)),layout()).isEmpty())
        assertTrue(QuestionLayoutDetector.detect(0,emptyList(),layout()).isEmpty())
    }
    @Test fun fragmentedPositionedTextFormsNumberedLine() {
        val runs = listOf(line("1",0.1,0.1,0.105),line(".",0.105,0.1,0.108),line("Soru",0.112,0.1,0.2))
        assertEquals(1,QuestionLayoutDetector.detect(0,runs,layout()).size)
    }
    @Test fun rectangleEditsStayInBoundsAndDoNotInvert() {
        val r=NormalizedRect(0.2,0.3,0.6,0.7)
        val moved=ProposalGeometry.move(r,4.0,-4.0)
        assertEquals(1.0,moved.right,0.0001); assertEquals(0.0,moved.top,0.0001)
        assertEquals(0.4,moved.right-moved.left,0.0001)
        val resized=ProposalGeometry.resize(r,0,0.9,0.9)
        assertTrue(resized.right>resized.left && resized.bottom>resized.top)
        assertNull(ProposalGeometry.between(0.2,0.2,0.201,0.201))
        assertEquals(NormalizedRect(0.0,0.0,1.0,1.0),ProposalGeometry.between(2.0,2.0,-1.0,-1.0))
    }
}
