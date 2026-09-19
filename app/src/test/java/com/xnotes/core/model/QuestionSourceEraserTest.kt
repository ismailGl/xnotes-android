package com.xnotes.core.model

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.*
import com.xnotes.core.history.*
import com.xnotes.core.infinite.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.*
import org.junit.Assert.*
import org.junit.Test

class QuestionSourceEraserTest {
    private val crop = Rect(100.0, 200.0, 200.0, 300.0)
    private val owner = "set:q"
    private fun stroke(x: Double, y: Double) = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
        mutableListOf(Sample(x,y,1.0), Sample(x+10,y,1.0)))
    private class Fixture {
        val page = Page(600.0, 800.0, pdfPage = 2)
        val notebook = Document(mutableListOf(page))
        val scratch = InfiniteDocument()
        val history = History()
    }
    private fun sync(f: Fixture) = QuestionInkProjector.replace(f.notebook,owner,2,crop,crop,f.scratch.items,FakeTextMeasurer())
    private fun erase(f: Fixture, x: Double = 150.0, y: Double = 250.0, area: Boolean = false) {
        val gesture = EraseSession(f.scratch, QuestionSourceEraser(f.page,crop,crop))
        gesture.erase(x,y,15.0,area)
        gesture.buildCommand()?.let(f.history::push)
        sync(f)
    }
    @Test fun normalStrokeErasedFromQuestionMutatesActualPage() {
        val f=Fixture(); val original=stroke(150.0,250.0); f.page.items.add(original)
        erase(f)
        assertFalse(f.page.items.contains(original)); assertTrue(f.page.items.isEmpty())
        assertTrue(f.scratch.items.isEmpty())
    }
    @Test fun questionStrokeErasesEverywhere() {
        val f=Fixture(); f.scratch.add(stroke(150.0,250.0)); sync(f)
        assertEquals(1,f.page.items.size)
        erase(f)
        assertTrue(f.scratch.items.isEmpty()); assertTrue(f.page.items.isEmpty())
    }
    @Test fun normalEraseIsAbsentFromSourceComposition() {
        val f=Fixture(); f.page.items.add(stroke(150.0,250.0))
        val normal=PageEraseSession(); normal.erase(f.page,150.0,250.0,15.0,false)
        val renderer=FakeRenderer(); QuestionSourceInk.paint(renderer,f.page,crop,owner)
        val empty=FakeRenderer(); QuestionSourceInk.paint(empty,Page(600.0,800.0),crop,owner)
        assertEquals(empty.ops,renderer.ops)
    }
    @Test fun mixedEraseIsOneNormalHistoryStepAndUndoRedoRestoresBothAuthorities() {
        val f=Fixture(); val normal=stroke(150.0,250.0); val scratch=stroke(155.0,250.0)
        f.page.items.add(normal); f.scratch.add(scratch); sync(f)
        erase(f)
        assertTrue(f.page.items.isEmpty())
        f.history.undo(); sync(f)
        assertSame(normal,f.page.items.first()); assertSame(scratch,f.scratch.items.single())
        assertFalse(f.history.canUndo)
        f.history.redo(); sync(f)
        assertTrue(f.page.items.isEmpty()); assertTrue(f.scratch.items.isEmpty())
    }
    @Test fun outsideSamplesAndCursorOverlapCannotEraseSourceInk() {
        val f=Fixture(); val source=stroke(101.0,250.0); f.page.items.add(source)
        val outside=stroke(95.0,250.0); f.scratch.add(outside)
        erase(f,95.0,250.0)
        assertSame(source,f.page.items.single()); assertTrue(f.scratch.items.isEmpty())
        val beyond=stroke(80.0,300.0); f.page.items.add(beyond)
        QuestionSourceEraser(f.page,crop,crop).erase(101.0,300.0,50.0,true)
        assertTrue(f.page.items.contains(beyond))
    }
    @Test fun activeProjectionIsNeverDeletedTwice() {
        val f=Fixture(); f.scratch.add(stroke(150.0,250.0)); sync(f)
        val projection=f.page.items.single()
        val source=QuestionSourceEraser(f.page,crop,crop)
        assertNull(source.erase(150.0,250.0,15.0,false)); assertNull(source.buildCommand())
        assertSame(projection,f.page.items.single())
        erase(f); f.history.undo(); sync(f)
        assertEquals(1,f.page.items.size); assertEquals(1,f.scratch.items.size)
        assertEquals(1,(f.page.items.single() as QuestionInkProjection).ink.size)
    }
    @Test fun translatedScaledRotatedWindowUsesProjectionMapping() {
        for (turn in 0..3) {
            val f=Fixture(); val window=Rect(-400.0,600.0,400.0,600.0)
            val viewport=CanvasViewport().apply { zoom=3.5; scrollX=-600.0; scrollY=120.0 }
            val screen=viewport.contentToViewport(Pt(-250.0,800.0))
            val sample=viewport.viewportToContent(screen)
            val source=QuestionInkProjector.mapping(window,crop,turn).apply(sample)
            val ink=stroke(source.x,source.y); f.page.items.add(ink)
            val gesture=QuestionSourceEraser(f.page,window,crop,turn)
            gesture.erase(sample.x,sample.y,20.0,false)
            assertTrue("turn $turn",f.page.items.isEmpty())
        }
    }
    @Test fun areaErasePreservesNormalFragmentsAndUndoIdentity() {
        val f=Fixture()
        val original=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),
            (120..270 step 5).map { Sample(it.toDouble(),250.0,1.0) }.toMutableList())
        f.page.items.add(original)
        erase(f,190.0,250.0,true)
        assertFalse(f.page.items.contains(original)); assertTrue(f.page.items.size >= 2)
        val fragments=f.page.items.toList()
        f.history.undo(); sync(f); assertSame(original,f.page.items.single())
        f.history.redo(); sync(f); assertEquals(fragments,f.page.items)
    }
}
