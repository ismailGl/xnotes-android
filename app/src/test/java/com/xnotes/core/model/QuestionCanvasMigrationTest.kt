package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class QuestionCanvasMigrationTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun scratchNavigationAndReopeningPreserveInfiniteInkAndViewWithoutSourceMutation() {
        val root = temp.newFolder()
        val sourceFile = java.io.File(root,"source.xnote").apply { writeText("source notebook bytes") }
        val sourceStroke = Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),mutableListOf(Sample(150.0,250.0,1.0)))
        val sourcePage = Page(600.0,800.0,pdfPage=0).apply { items.add(sourceStroke) }
        val source = Document(mutableListOf(sourcePage))
        val originalBounds = sourceStroke.bounds()
        val a = Question("a",0,NormalizedRect(.1,.2,.8,.9))
        val scratch = QuestionCanvasMigration.migrate(source,a,FakeTextMeasurer())
        assertTrue(scratch.items.isEmpty())
        scratch.add(sourceStroke.deepCopy(FakeTextMeasurer()).apply { translate(-5000.0,9000.0) })
        val viewport = com.xnotes.core.infinite.CanvasViewport().apply {
            widthPx=800; heightPx=600; scrollX=-7000.0; scrollY=8500.0; zoom=2.0
        }
        scratch.lastView=viewport.toWaypoint()
        val store = com.xnotes.platform.QuestionScratchStore(com.xnotes.platform.LocalQuestionFiles(root),"set",
            CanvasCodec(FakeImageCodec()),temp.newFolder())
        store.save("a",scratch.snapshotForWrite())
        val second = QuestionCanvasMigration.migrate(source,a.copy(id="b"),FakeTextMeasurer())
        store.save("b",second.snapshotForWrite())
        val reopened = store.load("a")!!
        assertEquals(scratch.items.single().bounds(),reopened.items.single().bounds())
        assertEquals(scratch.lastView,reopened.lastView)
        assertEquals(originalBounds,sourceStroke.bounds())
        assertEquals(1,sourcePage.items.size)
        assertEquals("source notebook bytes",sourceFile.readText())
        assertTrue(store.load("b")!!.items.isEmpty())
    }
    @Test fun pageInkKeepsCoordinatesAndWorkspacePersistsBeyondEveryCropEdge() {
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(150.0, 250.0, 1.0)))
        val page = Page(600.0, 800.0, pdfPage = 2).apply { items.add(stroke) }
        val question = Question("stable", 2, NormalizedRect(.1, .2, .8, .9))
        val canvas = QuestionCanvasMigration.migrate(Document(mutableListOf(page)), question, FakeTextMeasurer())
        assertTrue(canvas.items.isEmpty())
        canvas.add(stroke.deepCopy(FakeTextMeasurer()))
        val before = canvas.items.single().bounds()
        QuestionCanvasMigration.anchor(question.copy(crop = NormalizedRect(0.0, 0.0, 1.0, 1.0)), 600.0, 800.0)
        assertEquals(before, canvas.items.single().bounds())
        for ((x, y) in listOf(-10000.0 to 0.0, 10000.0 to 0.0, 0.0 to -10000.0, 0.0 to 10000.0)) {
            canvas.add(Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(x, y, 1.0))))
        }
        val codec = CanvasCodec(FakeImageCodec())
        val bytes = ByteArrayOutputStream().also { codec.write(canvas, it) }.toByteArray()
        val loaded = codec.read(bytes.inputStream(), temp.newFolder())
        assertEquals(canvas.items.map { it.bounds() }, loaded.items.map { it.bounds() })
        assertEquals(1, page.items.size)
    }
}
