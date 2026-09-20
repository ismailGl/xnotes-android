package com.xnotes.platform

import org.junit.Assert.*
import org.junit.Test

class BulkAnswerKeyTest {
    @Test fun parsesCaseInsensitiveSeparatedLettersAndReportsUnusedSuffix() {
        val ids = listOf("stable-2", "stable-1", "stable-3")
        val preview = BulkAnswerKey.parse("a, d\nB ; E", ids) { QuestionAnswerOptions() }
        assertTrue(preview.valid)
        assertEquals(listOf("A", "D", "B"), preview.answers)
        assertEquals(3, preview.count)
        assertEquals("E", preview.unused)
    }
    @Test fun validatesEachQuestionsChoiceCountAndLeavesShorterSuffixUntouched() {
        val ids = listOf("a", "b", "c")
        assertEquals(1, BulkAnswerKey.parse("a", ids) { QuestionAnswerOptions() }.count)
        val valid = BulkAnswerKey.parse("1) a, 2) b", ids) { QuestionAnswerOptions(optionCount = if (it == "b") 2 else 5) }
        assertTrue(valid.valid)
        val rejected = BulkAnswerKey.parse("AC", ids) { QuestionAnswerOptions(optionCount = if (it == "b") 2 else 5) }
        assertFalse(rejected.valid)
        assertTrue(rejected.error!!.contains("Question 2"))
    }
}
