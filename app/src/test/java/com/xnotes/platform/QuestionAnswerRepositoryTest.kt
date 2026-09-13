package com.xnotes.platform

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.DocumentCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QuestionAnswerRepositoryTest {
    @Test fun annotationDocumentsAreIndependentAndRetainCropCoordinates() = runBlocking {
        val root = temp.newFolder()
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val annotations = QuestionAnswerRepository(root, "set", codec, category = "annotations",
            blank = { com.xnotes.core.model.Document.blankPixels(width = 412.5, height = 185.25) })
        val annotation = annotations.load("a")
        annotation.pages.single().items.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            listOf(Sample(100.0, 90.0, 0.25), Sample(200.0, 150.0, 0.8))))
        annotations.save("a", annotation)
        repository(root).save("a", repository(root).load("a"))
        val restored = annotations.load("a")
        assertEquals(412.5, restored.pages.single().width, 1e-8)
        assertEquals(185.25, restored.pages.single().height, 1e-8)
        assertEquals(100.0, (restored.pages.single().items.single() as Stroke).samples.first().x, 1e-8)
        assertTrue(repository(root).load("a").pages.single().items.isEmpty())
        assertTrue(File(root, "set/annotations/a.xnote").isFile)
        assertFalse(restored.hasPdf)
    }
    @get:Rule val temp = TemporaryFolder()
    private fun repository(root: File, set: String = "set") = QuestionAnswerRepository(root, set,
        DocumentCodec(FakeImageCodec(), FakeTextMeasurer()))

    @Test fun missingAnswerIsBlankAndEditableInkRoundTripsIndependently() = runBlocking {
        val root = temp.newFolder()
        val repo = repository(root)
        val answer = repo.load("a")
        assertEquals(1, answer.pages.size)
        assertFalse(answer.hasPdf)
        assertNull(answer.pages.single().pdfPage)
        answer.pages.single().items.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            listOf(Sample(10.0, 20.0, 0.25), Sample(30.0, 40.0, 0.8))))
        repo.save("a", answer)
        val loaded = repository(root).load("a")
        val ink = loaded.pages.single().items.single() as Stroke
        assertEquals(Tool.PEN, ink.tool)
        assertEquals(0.25, ink.samples.first().pressure, 1e-6)
        assertTrue(repo.load("b").pages.single().items.isEmpty())
        assertTrue(repository(root, "other").load("a").pages.single().items.isEmpty())
        assertTrue(File(root, "set/answers/a.xnote").isFile)
    }

    @Test fun corruptAnswerIsNeverReplacedAndMetadataIsUntouched() = runBlocking {
        val root = temp.newFolder()
        val metadata = File(root, "set.json").apply { writeText("metadata") }
        val file = File(root, "set/answers/a.xnote").apply { parentFile!!.mkdirs(); writeText("corrupt") }
        try { repository(root).load("a"); fail("Corrupt answer accepted") } catch (_: Exception) { }
        assertEquals("corrupt", file.readText())
        assertEquals("metadata", metadata.readText())
    }

    @Test fun failedWritePropagatesAndUnsafePathsAreRejected() = runBlocking {
        val root = temp.newFolder()
        File(root, "set").writeText("not a directory")
        val repo = repository(root)
        val blank = com.xnotes.core.model.Document.blank()
        try { repo.save("a", blank); fail("Write failure swallowed") } catch (_: Exception) { }
        try { repo.load("../escape"); fail("Traversal accepted") } catch (_: IllegalArgumentException) { }
        assertEquals("not a directory", File(root, "set").readText())
    }
}
