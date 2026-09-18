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
    @Test fun pageInkKeepsCoordinatesAndWorkspacePersistsBeyondEveryCropEdge() {
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), mutableListOf(Sample(150.0, 250.0, 1.0)))
        val page = Page(600.0, 800.0, pdfPage = 2).apply { items.add(stroke) }
        val question = Question("stable", 2, NormalizedRect(.1, .2, .8, .9))
        val canvas = QuestionCanvasMigration.migrate(Document(mutableListOf(page)), question, FakeTextMeasurer())
        assertEquals(stroke.bounds(), canvas.items.single().bounds())
        assertNotSame(stroke, canvas.items.single())
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
