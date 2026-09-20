package com.xnotes.platform

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import java.io.File

class QuestionProgressRepositoryTest {
    @Test fun legacyStateDefaultsToFiveChoicesAndFlexibleTypesRoundTrip() {
        val legacy = QuestionProgressRepository.decode("""{"version":1,"lastQuestionId":"q","choices":{"q":"E"}}""")
        assertEquals(listOf("A", "B", "C", "D", "E"), legacy.optionsFor("q").choices)
        assertEquals("E", legacy.choices["q"])
        val state = QuestionProgress("eight", mapOf("eight" to "H"), mapOf(
            "eight" to QuestionAnswerOptions(optionCount = 8),
            "written" to QuestionAnswerOptions(QuestionType.OPEN_ENDED)))
        assertEquals(state, QuestionProgressRepository.decode(QuestionProgressRepository.encode(state)))
        assertTrue(state.optionsFor("written").choices.isEmpty())
    }
    @Test fun invalidOptionsAndOutOfRangeSelectionsAreHandledPerQuestion() {
        for (count in listOf(1, 9)) {
            try { QuestionAnswerOptions(optionCount = count); fail() } catch (_: IllegalArgumentException) { }
        }
        val decoded = QuestionProgressRepository.decode("""{"version":1,"choices":{"a":"H","b":"C","c":"A"},
            "answerOptions":{"a":{"type":"SINGLE_CHOICE","optionCount":8},
            "b":{"type":"SINGLE_CHOICE","optionCount":2},"c":{"type":"OPEN_ENDED"},
            "bad":{"type":"SINGLE_CHOICE","optionCount":999}}}""")
        assertEquals(mapOf("a" to "H"), decoded.choices)
        assertEquals(QuestionAnswerOptions(), decoded.optionsFor("bad"))
    }
    @get:Rule val temp = TemporaryFolder()
    @Test fun stateSurvivesReopeningAndIsSeparateFromQuestionMetadataAndInk() = runBlocking {
        val root = temp.newFolder()
        val metadata = File(root, "set.json").apply { writeText("existing metadata") }
        val legacy = File(root, "set/annotations/q.xnote").apply { parentFile!!.mkdirs(); writeText("legacy corrupt ink") }
        val repo = QuestionProgressRepository(root, "set")
        assertEquals(QuestionProgress(), repo.load())
        val state = QuestionProgress("q2", mapOf("q1" to "A", "q2" to "E"))
        repo.save(state)
        assertEquals(state, QuestionProgressRepository(root, "set").load())
        assertEquals(QuestionProgress(), QuestionProgressRepository(root, "other").load())
        assertEquals("existing metadata", metadata.readText())
        assertEquals("legacy corrupt ink", legacy.readText())
        repo.save(state.copy(choices = emptyMap()))
        assertTrue(repo.load().choices.isEmpty())
    }
    @Test fun unsafeIdsInvalidChoicesAndCorruptStateAreRejected() = runBlocking {
        val root = temp.newFolder()
        try { QuestionProgressRepository(root, "../escape"); fail() } catch (_: IllegalArgumentException) { }
        try { QuestionProgressRepository.encode(QuestionProgress(choices = mapOf("q" to "F"))); fail() }
        catch (_: IllegalArgumentException) { }
        val file = File(root, "set/state.json").apply { parentFile!!.mkdirs(); writeText("bad") }
        try { QuestionProgressRepository(root, "set").load(); fail() } catch (_: Exception) { }
        assertEquals("bad", file.readText())
    }
    @Test fun answersAndFeedbackAreDerivedAcrossChangesAndLegacyData() {
        val base = QuestionProgress(choices = mapOf("a" to "B", "missing" to "A"), answerKeys = mapOf("a" to "A"))
        assertEquals(QuestionResult.INCORRECT, base.resultFor("a"))
        assertEquals(QuestionResult.UNKNOWN, base.resultFor("missing"))
        assertEquals(QuestionResult.UNKNOWN, base.resultFor("unanswered"))
        assertEquals(QuestionResult.CORRECT, base.copy(choices = base.choices + ("a" to "A")).resultFor("a"))
        assertEquals(QuestionResult.INCORRECT, base.copy(choices = base.choices + ("a" to "C")).resultFor("a"))
        assertEquals(QuestionResult.UNKNOWN, base.copy(choices = base.choices - "a").resultFor("a"))
        assertTrue(base.copy(feedback = QuestionFeedback.IMMEDIATE).showsResult("a", listOf("a", "b")))
        assertFalse(base.copy(feedback = QuestionFeedback.ON_COMPLETION).showsResult("a", listOf("a", "b")))
        assertTrue(base.copy(feedback = QuestionFeedback.ON_COMPLETION, completed = setOf("b")).showsResult("a", listOf("a", "b")))
        assertFalse(base.showsResult("a", listOf("a")))
        assertTrue(base.copy(revealed = setOf("a")).showsResult("a", listOf("a")))
        val legacy = QuestionProgressRepository.decode("""{"version":1,"choices":{"a":"A"},"results":{"a":"INCORRECT"}}""")
        assertEquals(QuestionResult.UNKNOWN, legacy.resultFor("a"))
        assertTrue(legacy.answerKeys.isEmpty())
    }
    @Test fun answerKeysAndModesRoundTripInFolderState() = runBlocking {
        val root = temp.newFolder()
        val state = QuestionProgress("q", mapOf("q" to "D"), feedback = QuestionFeedback.ON_COMPLETION,
            completed = setOf("q"), answerKeys = mapOf("q" to "D"), revealed = setOf("q"))
        QuestionProgressRepository(root, "set").save(state)
        val restored = QuestionProgressRepository(root, "set").load()
        assertEquals(state, restored)
        assertEquals(QuestionResult.CORRECT, restored.resultFor("q"))
        assertEquals("D", org.json.JSONObject(java.io.File(root, "set/state.json").readText()).getJSONObject("answerKeys").getString("q"))
    }
}
