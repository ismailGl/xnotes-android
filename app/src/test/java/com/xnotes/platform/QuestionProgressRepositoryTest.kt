package com.xnotes.platform

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import java.io.File

class QuestionProgressRepositoryTest {
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
}
