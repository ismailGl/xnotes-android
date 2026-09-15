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
        assertTrue(gutter in 0.59..0.72)
        assertTrue(detected[2].crop.left > gutter)
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
    @Test fun ocrWordBoxesWithSkewAndSpacedPunctuationFormAnchors() {
        val runs = listOf(line("12",0.08,0.102,0.10), line(".",0.104,0.1,0.11),
            line("Cozunuz",0.118,0.101,0.3), line("A)",0.08,0.2,0.11),
            line("Soru",0.08,0.5,0.12), line("3",0.127,0.501,0.14))
        assertEquals(2,QuestionLayoutDetector.detect(0,runs,layout()).size)
    }
    @Test fun rasterMovesBoundaryAwayFromInkWithoutDiscardingDiagram() {
        val image=layout()
        for (y in 480..497) for (x in 100..400) image.ink[y*1000+x]=true
        val detected=QuestionLayoutDetector.detect(0,listOf(line("1) Ciziniz",0.08,0.1),line("2. Cozunuz",0.08,0.52)),image)
        assertTrue(detected.first().crop.bottom > 0.497)
        for (y in 513..516) for (x in 100..400) image.ink[y*1000+x]=true
        val adjusted=QuestionLayoutDetector.detect(0,listOf(line("1) Ciziniz",0.08,0.1),line("2. Cozunuz",0.08,0.52)),image)
        assertNotEquals(0.514,adjusted.first().crop.bottom,0.0001)
    }
    @Test fun columnWithoutRecognizedNumberStillConstrainsOtherColumn() {
        val detected=QuestionLayoutDetector.detect(0,listOf(line("1. Soru",0.06,0.1,0.44),
            line("A) Bir",0.06,0.3,0.44),line("Okunamayan soru",0.58,0.15,0.94),
            line("B) Iki",0.58,0.3,0.94)),layout())
        assertEquals(1,detected.size)
        assertTrue(detected.single().crop.right in 0.45..0.58)
    }
    @Test fun continuationFooterCannotDestroyColumnModes() {
        val body=listOf(line("8. Soru",0.07,0.1,0.47),line("9. Soru",0.07,0.4,0.47),
            line("10. Soru",0.07,0.76,0.47),line("11. Soru",0.53,0.101,0.94),
            line("12. Soru",0.53,0.33,0.94),line("13. Soru",0.53,0.6,0.94))
        val footer=(0..10).map { line("${it+1}. C",0.07+it*0.075,0.92,0.11+it*0.075) }
        val image=layout()
        for(y in 90..900) image.ink[y*1000+500]=true
        for(y in 470..530) for(x in 480..520) image.ink[y*1000+x]=true
        val d=QuestionLayoutDetector.analyze(12,body+footer,image)
        assertEquals(2,d.columnCount)
        assertNotNull(d.gutter)
        assertEquals(6,d.proposals.size)
        assertEquals(11,d.anchors.count { it.excluded != null })
        assertTrue(d.proposals.take(3).all { it.crop.right <= d.columnBounds[0].right })
        assertTrue(d.proposals.drop(3).all { it.crop.left >= d.columnBounds[1].left })
        assertTrue(d.proposals.first().crop.bottom > 0.12)
        assertTrue(d.proposals.all { it.crop.bottom < 0.92 })
        // Page identity has no influence on geometry.
        assertEquals(d.proposals.map { it.crop },QuestionLayoutDetector.detect(13,body+footer,image).map { it.crop })
    }
    @Test fun contaminatedGutterAndOutlierAnchorsKeepBimodalColumns() {
        val body=listOf(line("1. Soru",0.07,0.1,0.48),line("2. Soru",0.07,0.4,0.48),
            line("3. Soru",0.53,0.1,0.94),line("4. Soru",0.53,0.5,0.94),
            line("Publisher",0.47,0.3,0.56),line("23. value",0.29,0.7,0.4))
        val image=layout()
        for(y in 150..850) for(x in 490..510) image.ink[y*1000+x]=true
        val d=QuestionLayoutDetector.analyze(0,body,image)
        assertEquals(2,d.columnCount)
        assertTrue(d.gutter!!.center in 0.48..0.53)
        assertEquals(4,d.proposals.size)
        assertTrue(d.anchors.any { it.excluded?.contains("Interior numbering") == true })
        assertTrue(d.anchors.filter { it.excluded == null }.all { it.column != null })
    }
    @Test fun missingAnchorDoesNotLeaveCropExtendedThroughBlankPage() {
        val d=QuestionLayoutDetector.analyze(0,listOf(line("1. Explain",0.08,0.1),
            line("Printed body",0.08,0.2)),layout())
        assertEquals(1,d.columnCount)
        assertTrue(d.proposals.single().crop.bottom < 0.3)
    }
    @Test fun fullWidthSingleColumnTextDoesNotBecomeTwoColumnsFromInlineNumbers() {
        val d=QuestionLayoutDetector.analyze(0,listOf(line("1. Soru",0.07,0.1),
            line("Wide paragraph",0.07,0.2),line("2. Soru",0.07,0.5),
            line("Wide paragraph",0.07,0.6),line("12. term",0.55,0.3,0.8),
            line("13. term",0.55,0.7,0.8)),layout())
        assertEquals(1,d.columnCount)
    }
    @Test fun rightColumnNearGutterUsesDividerAndFinalClamp() {
        val image=layout()
        // Broad whitespace valley starts in short left text, but divider is at x=.49.
        for(y in 80..910) image.ink[y*1000+490]=true
        val d=QuestionLayoutDetector.analyze(12,listOf(
            line("8. Soru",0.07,0.10,0.40),line("9. Soru",0.07,0.40,0.40),
            line("11. Soru",0.53,0.10,0.94),line("12. Soru",0.53,0.35,0.94),
            line("Near gutter text",0.496,0.20,0.94)),image)
        assertEquals(2,d.columnCount)
        assertEquals(0.488,d.columnBounds[0].right,0.00001)
        assertEquals(0.493,d.columnBounds[1].left,0.00001)
        assertTrue(d.proposals.take(2).all { it.crop.right <= 0.488 })
        assertTrue(d.proposals.drop(2).all { it.crop.left >= 0.493 })
        // Simulate a later content union/padding that crosses both column bounds.
        val expanded=NormalizedRect(0.0,0.1,1.0,0.8)
        assertEquals(NormalizedRect(0.493,0.1,1.0,0.8),d.columnBounds[1].clamp(expanded))
        assertEquals(NormalizedRect(0.0,0.1,0.488,0.8),d.columnBounds[0].clamp(expanded))
        assertNull(d.columnBounds[1].clamp(NormalizedRect(0.1,0.1,0.2,0.2)))
        assertEquals(expanded,QuestionLayoutDetector.ColumnBounds(0.0,1.0).clamp(expanded))
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
