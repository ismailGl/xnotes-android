package com.xnotes.core

import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Test

class QuestionTextQualityTest {
    private fun runs(text: String = "Türkiye’de ışık, ölçü, çözüm ve doğru seçenek açıklaması") =
        (0..5).map { QuestionLayoutDetector.TextRun(text, NormalizedRect(0.1, 0.1 + it * 0.06, 0.8, 0.12 + it * 0.06)) }

    @Test fun turkishPrintedBodyKeepsPdfFastPath() {
        assertTrue(QuestionTextQuality.usable(runs()))
    }
    @Test fun absentSparseCorruptAndCollapsedTextUseOcr() {
        assertFalse(QuestionTextQuality.usable(emptyList()))
        assertFalse(QuestionTextQuality.usable(runs("12")))
        assertFalse(QuestionTextQuality.usable(runs("\uFFFD".repeat(45))))
        assertFalse(QuestionTextQuality.usable(runs("\uE001".repeat(45))))
        assertFalse(QuestionTextQuality.usable(runs().map { it.copy(box = NormalizedRect(0.1,0.1,0.8,0.12)) }))
        assertFalse(QuestionTextQuality.usable(runs().map { it.copy(box = NormalizedRect(0.1,0.96,0.8,0.99)) }))
    }
}
