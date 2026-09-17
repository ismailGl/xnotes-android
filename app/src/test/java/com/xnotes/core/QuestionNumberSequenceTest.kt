package com.xnotes.core

import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestionNumberSequenceTest {
    private fun run(s:String,y:Double,x:Double=.1)=QuestionLayoutDetector.TextRun(s,NormalizedRect(x,y,x+.015,y+.01))
    @Test fun sequencePrefersRealLaneOverInteriorTableValues() {
        assertTrue(QuestionNumberSequence.laneScore(listOf(run("9.",.1),run("10.",.4),run("11.",.8)))>
            QuestionNumberSequence.laneScore(listOf(run("4",.3),run("30",.32),run("10",.4))))
    }
    @Test fun duplicateAndBackwardBareTokensCannotSplitConsecutiveQuestions() {
        val runs=listOf(run("6.",.1),run("2",.2),run("7.",.4),run("7",.5),run("8.",.8))
        assertEquals(listOf("6.","7.","8."),QuestionNumberSequence.choose(runs).map { it.text })
    }
    @Test fun gapsUnknownNumbersAndExplicitResetsRemainPossible() {
        val runs=listOf(run("10.",.1),run("Unknown",.3),run("13.",.5),run("1.",.7),run("2.",.9))
        assertEquals(runs,QuestionNumberSequence.choose(runs))
        assertEquals(listOf(run("42.",.2)),QuestionNumberSequence.choose(listOf(run("42.",.2))))
    }
    @Test fun shiftedConsecutiveNumberHasEvidenceButUnrelatedInteriorNumberDoesNot() {
        val runs=listOf(run("7.",.1),run("8.",.4,.14),run("9.",.8,.12))
        assertTrue(QuestionNumberSequence.neighbours(runs[1],runs,.055))
        assertFalse(QuestionNumberSequence.neighbours(run("70",.5),runs,.055))
    }
}
