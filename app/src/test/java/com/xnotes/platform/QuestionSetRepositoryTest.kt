package com.xnotes.platform

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuestionSetRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun question(id: String) = Question(id, 4, NormalizedRect(0.1, 0.2, 0.8, 0.9))

    @Test fun persistsAndAppendsAcrossRepositoryInstancesWithoutCopyingPdf() {
        val dir = temp.newFolder("questions")
        val pdf = temp.newFile("source.pdf").apply { writeText("source bytes") }
        val first = QuestionSetRepository(dir).append("content://notes/one", "Physics", pdf, question("a"))
        val second = QuestionSetRepository(dir).append("content://notes/one", "Physics", pdf, question("b"))
        assertEquals(first.id, second.id)
        assertEquals(listOf(question("a"), question("b")), second.questions)
        assertEquals(64, second.sourcePdfSha256.length)
        val files = dir.listFiles()!!
        assertEquals(1, files.size)
        assertEquals(second, QuestionSetRepository.decode(files.single().readText()))
        pdf.writeText("replaced source")
        assertNotEquals(first.id, QuestionSetRepository(dir).append("content://notes/one", "Physics", pdf, question("c")).id)
    }

    @Test fun corruptMetadataIsNotOverwritten() {
        val dir = temp.newFolder("questions")
        val pdf = temp.newFile("source.pdf")
        QuestionSetRepository(dir).append("content://notes/one", "Title", pdf, question("a"))
        val file = dir.listFiles()!!.single()
        file.writeText("corrupt")
        assertThrows(Exception::class.java) {
            QuestionSetRepository(dir).append("content://notes/one", "Title", pdf, question("b"))
        }
        assertEquals("corrupt", file.readText())
    }

    @Test fun writeFailurePropagates() {
        val notDirectory = temp.newFile("blocked")
        val pdf = temp.newFile("source.pdf")
        assertThrows(Exception::class.java) {
            QuestionSetRepository(notDirectory).append("content://notes/one", "Title", pdf, question("a"))
        }
        assertEquals("", notDirectory.readText())
    }

    @Test fun rejectsInvalidCropsAndUnsupportedVersions() {
        assertThrows(IllegalArgumentException::class.java) { NormalizedRect(0.5, 0.0, 0.1, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedRect(0.0, 0.0, Double.NaN, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { QuestionSetRepository.decode("{\"version\":2}") }
    }

    @Test fun lookupMatchesUriAndHashWithoutWritingAnything() {
        val dir = temp.newFolder("questions")
        val pdf = temp.newFile("source.pdf").apply { writeText("original PDF") }
        val repo = QuestionSetRepository(dir)
        assertNull(repo.find("content://notes/one", pdf))
        assertEquals(0, dir.listFiles()!!.size)
        val saved = repo.append("content://notes/one", "Title", pdf, question("a"))
        val file = dir.listFiles()!!.single()
        val bytes = file.readBytes()
        assertEquals(saved.id, repo.find("content://notes/one", pdf)!!.id)
        assertEquals(question("a"), repo.find("content://notes/one", pdf)!!.entries.single().question)
        assertNull(repo.find("content://notes/two", pdf))
        pdf.writeText("different PDF")
        assertNull(repo.find("content://notes/one", pdf))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun viewerIsolatesBadEntriesButWriterStillRejectsThem() {
        val dir = temp.newFolder("questions")
        val pdf = temp.newFile("source.pdf")
        val repo = QuestionSetRepository(dir)
        repo.append("content://notes/one", "Title", pdf, question("a"))
        repo.append("content://notes/one", "Title", pdf, question("b"))
        val file = dir.listFiles()!!.single()
        val json = org.json.JSONObject(file.readText())
        json.getJSONArray("questions").getJSONObject(0).getJSONObject("crop").put("right", -1)
        file.writeText(json.toString())
        val entries = repo.find("content://notes/one", pdf)!!.entries
        assertEquals(2, entries.size)
        assertNull(entries[0].question)
        assertNotNull(entries[0].error)
        assertEquals(question("b"), entries[1].question)
        assertThrows(IllegalArgumentException::class.java) { QuestionSetRepository.decode(file.readText()) }
    }

    @Test fun rejectsMismatchedMetadataAndMissingSource() {
        val dir = temp.newFolder("questions")
        val pdf = temp.newFile("source.pdf")
        val repo = QuestionSetRepository(dir)
        repo.append("content://notes/one", "Title", pdf, question("a"))
        val file = dir.listFiles()!!.single()
        file.writeText(org.json.JSONObject(file.readText()).put("sourcePdfSha256", "wrong").toString())
        assertThrows(IllegalArgumentException::class.java) { repo.find("content://notes/one", pdf) }
        assertTrue(pdf.delete())
        assertThrows(java.io.IOException::class.java) { repo.find("content://notes/one", pdf) }
    }
}
