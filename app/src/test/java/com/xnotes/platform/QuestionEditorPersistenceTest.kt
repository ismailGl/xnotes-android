package com.xnotes.platform

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question
import com.xnotes.ui.QuestionPageReview
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuestionEditorPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val crop = NormalizedRect(.1, .2, .8, .9)

    @Test fun cropCorrectionChangesOnlyGeometryAcrossRepositoryRecreation() = runBlocking {
        val directory = temp.newFolder()
        val files = LocalQuestionFiles(directory)
        val pdf = temp.newFile().apply { writeText("source") }
        val original = Question("stable", 2, crop)
        val set = QuestionSetRepository(files).append("notebook", "Title", pdf, original)
        val root = JSONObject(files.read("${set.id}.json")!!.toString(Charsets.UTF_8))
        root.put("futureAnswerKey", JSONObject().put("detected", false))
        root.getJSONArray("questions").getJSONObject(0).put("futureField", "preserve")
        files.write("${set.id}.json") { it.write(root.toString().toByteArray()) }
        val progress = QuestionProgress("stable", mapOf("stable" to "B"), feedback = QuestionFeedback.ON_COMPLETION)
        QuestionProgressRepository(files, set.id).save(progress)
        files.write("${set.id}/answers/stable.xnote") { it.write(byteArrayOf(1, 2, 3)) }
        val corrected = NormalizedRect(.05, .15, .9, .95)
        val updated = QuestionSetRepository(LocalQuestionFiles(directory)).updateCrop("notebook", pdf, "stable", corrected)
        assertEquals(original.copy(crop = corrected), updated)
        assertEquals(updated, QuestionSetRepository(files).find("notebook", pdf)!!.entries.single().question)
        assertEquals(progress, QuestionProgressRepository(LocalQuestionFiles(directory), set.id).load())
        assertArrayEquals(byteArrayOf(1, 2, 3), files.read("${set.id}/answers/stable.xnote"))
        val saved = JSONObject(files.read("${set.id}.json")!!.toString(Charsets.UTF_8))
        assertFalse(saved.getJSONObject("futureAnswerKey").getBoolean("detected"))
        assertEquals("preserve", saved.getJSONArray("questions").getJSONObject(0).getString("futureField"))
    }

    @Test fun deletionRemovesInkAndProgressAndRecoversAfterInterruptedCleanup() = runBlocking {
        val disk = LocalQuestionFiles(temp.newFolder())
        var fail = true
        val files = object : QuestionFiles by disk {
            override fun delete(path: String) {
                if (fail && path.endsWith("questions/a")) error("provider unavailable")
                disk.delete(path)
            }
        }
        val pdf = temp.newFile().apply { writeText("original PDF") }
        val repo = QuestionSetRepository(files)
        val set = repo.append("note", "Test", pdf, Question("a", 0, crop))
        repo.append("note", "Test", pdf, Question("b", 1, crop))
        QuestionProgressRepository(files, set.id).save(QuestionProgress("a",
            choices = mapOf("a" to "A", "b" to "B"), completed = setOf("a", "b")))
        files.write("${set.id}/questions/a/ink.xcanvas") { it.write(byteArrayOf(1)) }
        files.write("${set.id}/answers/a.xnote") { it.write(byteArrayOf(2)) }
        files.write("${set.id}/questions/b/ink.xcanvas") { it.write(byteArrayOf(3)) }
        assertThrows(IllegalStateException::class.java) { repo.deleteQuestion("note", pdf, "a") }
        fail = false
        assertEquals(listOf("b"), QuestionSetRepository(files).find("note", pdf)!!.entries.map { it.question!!.id })
        assertNull(files.read("${set.id}/questions/a/ink.xcanvas"))
        assertNull(files.read("${set.id}/answers/a.xnote"))
        assertArrayEquals(byteArrayOf(3), files.read("${set.id}/questions/b/ink.xcanvas"))
        val progress = QuestionProgressRepository(files, set.id).load()
        assertEquals(mapOf("b" to "B"), progress.choices)
        assertEquals(setOf("b"), progress.completed)
        assertEquals("original PDF", pdf.readText())
    }

    @Test fun failedEncodeNeverTruncatesDurableBytes() {
        val files = LocalQuestionFiles(temp.newFolder())
        files.write("set/state.json") { it.write("saved".toByteArray()) }
        assertThrows(IllegalStateException::class.java) {
            files.write("set/state.json") { it.write("partial".toByteArray()); error("encode failed") }
        }
        assertEquals("saved", files.read("set/state.json")!!.toString(Charsets.UTF_8))
    }

    @Test fun feedbackAndCompletionPersistWithoutInferringCorrectness() {
        val state = QuestionProgress(choices = mapOf("q" to "A"), completed = setOf("open"), feedback = QuestionFeedback.IMMEDIATE)
        assertEquals(state, QuestionProgressRepository.decode(QuestionProgressRepository.encode(state)))
        val review = QuestionPageReview.create(2, listOf(Question("q", 2, crop), Question("other", 3, crop)), state)
        assertEquals(1, review.overlays.size)
        assertEquals(QuestionResult.UNKNOWN, review.overlays.single().result)
        assertNull(review.overlays.single().indicator)
        assertNull(review.overlays.single().color)
        val graded = QuestionPageReview.create(2, listOf(Question("q", 2, crop)), state.copy(results = mapOf("q" to QuestionResult.CORRECT)))
        assertNotNull(graded.overlays.single().color)
        assertEquals("✓", graded.overlays.single().indicator)
    }
}
