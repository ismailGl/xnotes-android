package com.xnotes.core.model

import com.xnotes.core.*
import com.xnotes.core.geometry.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.*
import com.xnotes.format.DocumentCodec
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class QuestionInkProjectionTest {
    private val measurer = FakeTextMeasurer()
    private val window = Rect(100.0,200.0,200.0,300.0)
    private fun stroke(vararg points: Pt) = Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),
        points.map { Sample(it.x,it.y,1.0) }.toMutableList())
    @Test fun insideOutsideAndCrossingAreLinkedAndClippedWithoutChangingScratch() {
        val normal=stroke(Pt(10.0,10.0))
        val page=Page(600.0,800.0,pdfPage=2).apply { items.add(normal) }
        val doc=Document(mutableListOf(page))
        val inside=stroke(Pt(150.0,250.0),Pt(200.0,300.0))
        val outside=stroke(Pt(-5000.0,9000.0))
        val crossing=stroke(Pt(0.0,350.0),Pt(400.0,350.0))
        val scratch=listOf(inside,outside,crossing)
        val before=scratch.map { it.bounds() }
        fun sync(items: List<CanvasItem>) = QuestionInkProjector.replace(doc,"set:q",2,window,window,items,measurer)
        sync(scratch)
        val projection=page.items.filterIsInstance<QuestionInkProjection>().single()
        assertEquals(2,projection.ink.size)
        assertEquals(window,projection.clip)
        val renderer=FakeRenderer(); projection.paint(renderer)
        assertEquals(listOf("save","clipRect"),renderer.ops.take(2))
        assertEquals("restore",renderer.ops.last())
        assertEquals(before,scratch.map { it.bounds() })
        // Erase/undo/redo replace the same owner, never accumulate copies.
        sync(listOf(outside)); assertEquals(listOf(normal),page.items)
        sync(scratch); sync(scratch)
        assertEquals(2,page.items.size); assertSame(normal,page.items.first())
        val codec=DocumentCodec(FakeImageCodec(),measurer)
        val bytes=ByteArrayOutputStream().also { codec.write(doc,it) }.toByteArray()
        val reopened=codec.read(bytes.inputStream())
        QuestionInkProjector.replace(reopened,"set:q",2,window,window,scratch,measurer)
        assertEquals(2,reopened.pages.single().items.size)
        assertEquals(window,reopened.pages.single().items.filterIsInstance<QuestionInkProjection>().single().clip)
    }
    @Test fun normalNotebookInkIsVisibleOnlyWithinCropAndActiveScratchIsNotDoubled() {
        val normal=stroke(Pt(150.0,250.0))
        val outside=stroke(Pt(-9000.0,-9000.0))
        val active=QuestionInkProjection("set:q",window,listOf(stroke(Pt(200.0,300.0))))
        val other=QuestionInkProjection("set:other",window,listOf(stroke(Pt(250.0,350.0))))
        val page=Page(600.0,800.0,pdfPage=2).apply { items.addAll(listOf(normal,outside,active,other)) }
        val fake=FakeRenderer()
        val clips=mutableListOf<Rect>()
        val renderer=object: com.xnotes.core.pal.Renderer by fake {
            override fun clipRect(rect:Rect) { clips.add(rect); fake.clipRect(rect) }
        }
        QuestionSourceInk.paint(renderer,page,window,"set:q")
        assertEquals(window,clips.first())
        val expected=FakeRenderer().also { normal.paint(it); other.paint(it) }
        assertEquals(listOf("save","clipRect") + expected.ops + listOf("restore"),fake.ops)
        assertEquals(expected.circles,fake.circles)
        assertEquals(expected.segments,fake.segments)
        assertEquals(listOf(normal,outside,active,other),page.items)
        assertTrue(QuestionCanvasMigration.migrate(Document(mutableListOf(page)),
            Question("q",2,NormalizedRect(.1,.1,.5,.5)),measurer).items.isEmpty())
    }
    @Test fun transformedWindowAndRotatedImageMapWithoutViewportCoordinates() {
        val source=Rect(60.0,160.0,300.0,400.0)
        val moved=Rect(-5000.0,8000.0,600.0,800.0)
        assertEquals(Pt(210.0,360.0),QuestionInkProjector.mapping(moved,source).apply(moved.center))
        assertEquals(Pt(60.0,160.0),QuestionInkProjector.mapping(moved,source).apply(Pt(moved.x,moved.y)))
        assertEquals(Pt(60.0,560.0),QuestionInkProjector.mapping(moved,source,1).apply(Pt(moved.x,moved.y)))
        assertEquals(Pt(360.0,560.0),QuestionInkProjector.mapping(moved,source,2).apply(Pt(moved.x,moved.y)))
        assertEquals(Pt(360.0,160.0),QuestionInkProjector.mapping(moved,source,3).apply(Pt(moved.x,moved.y)))
    }
}
