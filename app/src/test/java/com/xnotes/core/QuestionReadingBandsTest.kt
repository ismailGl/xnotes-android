package com.xnotes.core

import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestionReadingBandsTest {
    private fun run(text:String,x:Double,y:Double,right:Double=x+.02)=
        QuestionLayoutDetector.TextRun(text,NormalizedRect(x,y,right,y+.01))
    private val regions=listOf(
        QuestionPageRegions.Region(NormalizedRect(0.0,0.0,.39,1.0),QuestionPageRegions.Role.QUESTIONS,.9,"left",false),
        QuestionPageRegions.Region(NormalizedRect(.40,0.0,.75,1.0),QuestionPageRegions.Role.QUESTIONS,.9,"right",false),
        QuestionPageRegions.Region(NormalizedRect(.75,0.0,1.0,1.0),QuestionPageRegions.Role.INSTRUCTIONAL,.9,"sidebar"))
    private val runs=listOf(run("1.",.08,.1),run("2.",.08,.4),run("3.",.42,.1),run("4.",.42,.4),
        run("A substantial spanning question describes the diagram",.12,.8,.70),
        run("Another complete line continues across the old gutter",.12,.82,.70))
    private fun raster(marker:Boolean)=QuestionLayoutDetector.Layout(1000,1400,BooleanArray(1400000)).also { l ->
        for(y in 940..1070) for(x in 130..720) l.ink[y*1000+x]=true
        if(marker) for(y in 910..923) for(x in 80..86) l.ink[y*1000+x]=true
    }
    @Test fun spanningGraphicAndMarginGlyphCreateLocalBandWithoutChangingSidebarRole() {
        val result=QuestionReadingBands.refine(regions,runs,raster(true))
        val question=result.filter { it.role==QuestionPageRegions.Role.QUESTIONS }
        assertEquals(3,question.size)
        assertEquals(regions.last(),result.single { it.role==QuestionPageRegions.Role.INSTRUCTIONAL })
        val wide=question.single { it.recoveredStart!=null }
        assertEquals(0.0,wide.box.left,0.0)
        assertEquals(.75,wide.box.right,0.0)
        assertTrue(wide.box.top in .63.. .65)
        assertTrue(question.filter { it!=wide }.all { it.box.bottom==wide.box.top })
    }
    @Test fun crossingContentAloneCannotInventAQuestionStart() {
        assertEquals(regions,QuestionReadingBands.refine(regions,runs,raster(false)))
    }
    @Test fun graphicAloneCannotCollapseTwoIndependentColumns() {
        assertEquals(regions,QuestionReadingBands.refine(regions,runs.take(4),raster(true)))
    }
}
