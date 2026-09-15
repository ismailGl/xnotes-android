package com.xnotes.core

import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestionPageRegionsTest {
    private fun line(t:String,x:Double,y:Double,r:Double=x+0.20)=
        QuestionLayoutDetector.TextRun(t,NormalizedRect(x,y,r,y+0.015))
    private fun analyze(runs:List<QuestionLayoutDetector.TextRun>)=QuestionLayoutDetector.analyze(0,runs,
        QuestionLayoutDetector.Layout(800,1000,BooleanArray(800000)))
    private fun panel(x:Double)=listOf(line("Konuyu ogrenelim",x,0.08),line("Ornek",x,0.2),
        line("Detailed explanation of physical quantities",x,0.25),line("Cozum",x,0.4),
        line("Instructional explanation with substantial prose",x,0.45),
        line("More teaching material continues down this panel",x,0.8))
    @Test fun leftSidebarThenTwoQuestionColumnsUseLocalCoordinates() {
        val d=analyze(panel(0.04)+listOf(line("1. Explain this question",0.35,0.12,0.60),
            line("2. Explain another question",0.35,0.55,0.60),
            line("3. Explain this question",0.69,0.12,0.94),line("4. Explain another question",0.69,0.55,0.94)))
        assertTrue(d.regions.any { it.role==QuestionPageRegions.Role.INSTRUCTIONAL })
        assertEquals(2,d.columnCount)
        assertEquals(4,d.proposals.size)
        assertTrue(d.proposals.all { it.crop.left>=0.24 })
        assertTrue(d.proposals.take(2).all { it.crop.right<d.proposals.last().crop.left })
    }
    @Test fun rightSidebarAndWideQuestionsDoNotForceAnInternalSplit() {
        val d=analyze(panel(0.76)+listOf(line("1. Explain the wide illustration",0.06,0.12,0.68),
            line("2. Explain another wide illustration",0.06,0.55,0.68)))
        assertEquals(1,d.columnCount)
        assertEquals(2,d.proposals.size)
        assertTrue(d.proposals.all { it.crop.right<=0.76 && it.crop.right>=0.68 })
    }
    @Test fun noteInsideQuestionDoesNotBecomeAnInstructionalPanel() {
        val d=analyze(listOf(line("1. Explain the note",0.08,0.12,0.9),line("Ornek",0.15,0.2),
            line("Cozum",0.15,0.24),line("2. Another real question",0.08,0.7,0.9)))
        assertFalse(d.regions.any { it.role==QuestionPageRegions.Role.INSTRUCTIONAL })
        assertEquals(2,d.proposals.size)
    }
    @Test fun contentsPageIsNonQuestionDespiteNumberedUnits() {
        val d=analyze((1..6).flatMap { n -> listOf(line("$n. UNIT",0.1,0.08+n*0.12,0.3),
            line("Topic ................ ${n*10}",0.32,0.08+n*0.12,0.9)) })
        assertTrue(d.proposals.isEmpty())
        assertTrue(d.regions.any { it.role==QuestionPageRegions.Role.DOCUMENT })
    }
    @Test fun bottomKeyIsExcludedWhileShortLowQuestionSurvives() {
        val d=analyze(listOf(line("1. Explain",0.08,0.1,0.9),line("2. Cozunuz",0.08,0.86,0.9),
            line("1.D 2.C 3.B 4.A",0.2,0.925,0.8)))
        assertEquals(2,d.proposals.size)
        assertTrue(d.proposals.last().crop.top>0.85)
        assertTrue(d.proposals.all { it.crop.bottom<0.925 })
        assertTrue(d.regions.any { it.role==QuestionPageRegions.Role.ANSWER_KEY })
    }
    @Test fun dedicatedCompactAnswerGridIsExcludedAwayFromFooter() {
        val grid=(0..3).flatMap { row -> (0..2).map { col ->
            line("${row*3+col+1}. ${('A'.code+col).toChar()}",0.15+col*0.18,0.2+row*0.035,0.20+col*0.18) } }
        val d=analyze(grid)
        assertTrue(d.proposals.isEmpty())
        assertTrue(d.regions.any { it.role==QuestionPageRegions.Role.ANSWER_KEY && it.box.top<0.3 })
    }
    @Test fun middleKeyDoesNotRemoveQuestionBelowIt() {
        val d=analyze(listOf(line("1. Explain",0.08,0.1,0.9),line("1D 2C 3B 4A",0.2,0.4,0.8),
            line("2. Explain",0.08,0.6,0.9)))
        assertEquals(2,d.proposals.size)
        assertTrue(d.proposals.first().crop.bottom<0.4)
        assertTrue(d.proposals.last().crop.top>0.59)
    }
    @Test fun numberedDiagramExitsAndTableValuesAreNotAnswerKeys() {
        val d=analyze(listOf(line("1. Interpret this diagram",0.08,0.12,0.9),
            line("1. exit 2. exit 3. exit 4. exit",0.15,0.87,0.9),
            line("Explain which path reaches the correct result",0.1,0.90,0.9),
            line("A) First B) Second C) Third",0.1,0.93,0.9),
            line("1. 20 2. 30 3. 40",0.3,0.5,0.8)))
        assertFalse(d.regions.any { it.role==QuestionPageRegions.Role.ANSWER_KEY })
        assertTrue(d.proposals.first().crop.bottom>0.94)
    }
    @Test fun proseQuestionsBesideAKeyAreNotClassifiedAsPartOfIt() {
        val d=analyze(listOf(line("1. Explain this table",0.08,0.1,0.9),
            line("1D 2C 3B",0.1,0.5,0.35),line("2. Explain",0.6,0.5,0.9)))
        val key=d.regions.single { it.role==QuestionPageRegions.Role.ANSWER_KEY }
        assertTrue(key.box.right<0.6)
        assertFalse(d.anchors.any { it.text=="2. Explain" && it.excluded=="answer_key" })
    }
    @Test fun choiceLikeDiagramLabelsWithFollowingProseAreNotKeys() {
        val d=analyze(listOf(line("1. Interpret the labelled diagram",0.08,0.1,0.9),
            line("1A 2B 3C",0.2,0.85,0.8),
            line("Explain the relationships between these labelled nodes",0.2,0.88,0.9)))
        assertFalse(d.regions.any { it.role==QuestionPageRegions.Role.ANSWER_KEY })
        assertTrue(d.proposals.first().crop.bottom>0.89)
    }
    @Test fun fragmentedInstructionalTitleStillIdentifiesPanel() {
        val sidebar=panel(0.04).filterNot { it.text in listOf("Konuyu ogrenelim","Ornek","Cozum") }+
            listOf(line("KONUYU",0.04,0.08,0.10),line("OGRENELIM",0.108,0.08,0.24))
        val d=analyze(sidebar+listOf(line("1. Main question",0.35,0.12,0.9),line("2. Main question",0.35,0.55,0.9)))
        assertTrue(d.regions.any { it.role==QuestionPageRegions.Role.INSTRUCTIONAL })
        assertEquals(2,d.proposals.size)
    }

}
