package com.xnotes.core

import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestionAnchorRecoveryTest {
    private fun line(t:String,x:Double,y:Double,r:Double=x+0.30)=QuestionLayoutDetector.TextRun(t,NormalizedRect(x,y,r,y+0.010))
    private fun layout()=QuestionLayoutDetector.Layout(1000,1400,BooleanArray(1400000))
    private val bounds=QuestionLayoutDetector.ColumnBounds(0.51,1.0)
    private fun select(lines:List<QuestionLayoutDetector.TextRun>,image:QuestionLayoutDetector.Layout=layout()) =
        QuestionAnchorDetector.select(lines,bounds,1,image,"OCR",listOf(0.125))
    @Test fun bareFirstNumberAndDamagedMarkerAreRecoveredAtMargin() {
        val d=select(listOf(line("3",0.539,0.126,0.543),line("A substantial printed question begins here",0.565,0.125),
            line(")4.",0.526,0.5,0.546),line("Another printed question begins here",0.565,0.5)))
        assertEquals(2,d.accepted.size)
        assertTrue(d.diagnostics.all { it.source=="visual recovered" })
    }
    @Test fun equationAndRomanMisreadInsideQuestionAreRejected() {
        val d=select(listOf(line("4.",0.534,0.126,0.546),line("1.2B-A",0.566,0.126,0.69),
            line("Substantial question explanation follows",0.565,0.177),line("1.",0.593,0.348,0.599),
            line("A statement inside this question",0.615,0.348),line("5.",0.534,0.5,0.546),
            line("Another substantial question begins",0.565,0.5)))
        assertEquals(2,d.accepted.size)
        assertTrue(d.diagnostics.filter { it.box.left>0.55 }.all { it.source=="rejected" && it.reason.contains("interior number") })
    }
    @Test fun absentNumberRecoveredFromSmallRasterGlyphBetweenAnchors() {
        val image=layout()
        for(y in 484..494) for(x in 535..540) image.ink[y*1000+x]=true
        val d=select(listOf(line("1. Initial question",0.534,0.125),
            line("This substantial question has no recognized marker",0.565,0.345),
            line("3. Final question",0.534,0.65)),image)
        assertEquals(3,d.accepted.size)
        assertTrue(d.diagnostics.any { it.source=="visual recovered" && it.reason.contains("raster marker") })
    }
    @Test fun unnumberedPopulatedFirstRegionRecoveredButBlankColumnIsNot() {
        val d=select(listOf(line("A substantial first question without any OCR number",0.565,0.125),
            line("A further line of the printed question",0.565,0.14),line("5. Later question",0.534,0.5)))
        assertTrue(d.accepted.first().box.top<0.14)
        assertTrue(select(emptyList()).accepted.isEmpty())
    }
    @Test fun separatedLargeContentGroupsTriggerGapRecovery() {
        val d=select(listOf(line("1. Initial question",0.534,0.125),
            line("A long substantial second question has lost its number entirely",0.565,0.4),
            line("More substantial printed text supports a separate content cluster",0.565,0.415),
            line("3. Final question",0.534,0.8)))
        assertEquals(3,d.accepted.size)
        assertTrue(d.diagnostics.any { it.reason.contains("oversized") })
    }
    @Test fun compactFooterStripRejectedButLowQuestionRetained() {
        val runs=listOf(line("1. Substantial question",0.09,0.12),line("2. Legitimate low question",0.09,0.86),
            line("1.D",0.72,0.918,0.74),line("2.C",0.77,0.918,0.79),line("3.B",0.82,0.918,0.84))
        val d=QuestionLayoutDetector.analyze(0,runs,layout())
        assertTrue(d.anchors.filter { it.box.top>0.9 }.all { it.source=="rejected" && it.reason.contains("footer") })
        assertTrue(d.proposals.any { it.crop.top>0.85 && it.crop.top<0.9 })
    }
    @Test fun populatedColumnWithZeroNumericAnchorsGetsEvidenceBasedRecovery() {
        val d=select(listOf(line("A substantial first question whose marker is unreadable",0.565,0.125),
            line("The body provides enough positioned text to support recovery",0.565,0.14)))
        assertEquals(1,d.accepted.size)
        assertEquals("visual recovered",d.diagnostics.single().source)
        assertTrue(d.diagnostics.single().reason.contains("first region"))
        assertTrue(select(listOf(line("A) Choice",0.565,0.125),line("II. statement",0.565,0.14))).accepted.isEmpty())
    }
    @Test fun lowConfidenceRecoveryIsReviewableAndRetainsPdfSourceForConfirmedAnchors() {
        val d=QuestionLayoutDetector.analyze(0,listOf(line("1. Explain the question",0.08,0.12),
            line("2",0.08,0.4,0.09),line("Explain this other substantial question",0.12,0.4)),layout(),QuestionTextSource.PDF_TEXT)
        assertTrue(d.anchors.any { it.source=="PDF text" })
        assertTrue(d.anchors.any { it.source=="visual recovered" })
        assertTrue(d.proposals.last().reasons.any { it.contains("Recovered question start") })
        assertFalse(d.proposals.last().accepted)
    }
    @Test fun isolatedMarginNumberWithoutBodyIsRejected() {
        assertTrue(select(listOf(line("1.",0.534,0.125,0.546))).accepted.isEmpty())
    }
    @Test fun imageFirstMarkerUsesRasterAndDelayedTextInBothCoordinateFrames() {
        val runs=listOf(line("4.",0.534,0.12,0.546),
            line("Explain the substantial diagram shown above",0.565,0.43),
            line("5. Another substantial question",0.534,0.70))
        val image=layout()
        for(y in 200..550) for(x in 570..850) image.ink[y*1000+x]=true
        val full=select(runs,image)
        assertTrue(full.accepted.any { it.text=="4." })
        assertTrue(full.diagnostics.any { it.reason.contains("substantial raster block") })
        assertFalse(select(runs).accepted.any { it.text=="4." })
        val localRuns=runs.map { it.copy(box=it.box.let { b ->
            NormalizedRect((b.left-.51)/.49,b.top,(b.right-.51)/.49,b.bottom)
        }) }
        val localImage=QuestionLayoutDetector.Layout(490,1400,BooleanArray(490*1400) { i ->
            image.ink[(i/490)*1000+i%490+510]
        })
        val local=QuestionAnchorDetector.select(localRuns,QuestionLayoutDetector.ColumnBounds(0.0,1.0),
            1,localImage,"OCR",emptyList(),.49)
        assertEquals(full.accepted.map { it.text },local.accepted.map { it.text })
    }
    @Test fun optionPrefixedNumberCannotSplitActiveQuestion() {
        val d=select(listOf(line("1. Explain this substantial question",0.534,0.12),
            line("A)",0.520,0.30,0.531),line("2.",0.534,0.30,0.546),
            line("A long option is still part of the question",0.565,0.30),
            line("2. Next substantial question",0.534,0.60)))
        assertEquals(listOf(.12,.60),d.accepted.map { it.box.top })
        assertTrue(d.diagnostics.any { it.reason.contains("inline text or option prefix") })
    }
}
