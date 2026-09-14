package com.xnotes.ui

import com.xnotes.core.model.*
import com.xnotes.platform.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuestionDetectionSessionTest {
    @get:Rule val temp=TemporaryFolder()
    private suspend fun until(check: () -> Boolean) = withTimeout(5000) { while(!check()) delay(5) }
    private class Reader : QuestionPageReader {
        @Volatile var closed=false
        @Volatile var started=false
        var failPage: Int?=null
        var pause=false
        override fun page(index: Int): PdfTextExtractor.PageData {
            started=true
            if(pause) Thread.sleep(80)
            if(index == failPage) error("Malformed page")
            return PdfTextExtractor.PageData(listOf(QuestionLayoutDetector.TextRun("1. Soru",NormalizedRect(0.1,0.1,0.8,0.12))),
                QuestionLayoutDetector.Layout(100,100,BooleanArray(10000)))
        }
        override fun close() { closed=true }
    }
    @Test fun scanAndEditsAreTemporaryAndCommitSavesOnlyAccepted() = runBlocking {
        val dir=temp.newFolder(); val pdf=temp.newFile(); val reader=Reader()
        val work=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val repo=QuestionSetRepository(dir)
            val session=QuestionDetectionSession(pdf,"uri","Title",listOf(0,2),repo,work,{true},{reader})
            session.scan(listOf(0,2))
            until { !session.busy }
            assertTrue(reader.closed); assertEquals(2,session.proposals.size)
            assertEquals(0,dir.listFiles()!!.size)
            val first=session.proposals.first()
            session.accept(first.id,true)
            session.edit(first.id,NormalizedRect(0.1,0.1,0.7,0.5))
            assertFalse(session.proposals.first().accepted)
            session.accept(first.id,true)
            var done=false
            session.commit { done=true }
            until { done }
            val saved=repo.find("uri",pdf)!!.entries.mapNotNull { it.question }
            assertEquals(1,saved.size); assertEquals(first.id,saved.single().id)
            assertEquals(0.5,saved.single().crop.bottom,0.0001)
        } finally { work.cancel() }
    }
    @Test fun cancelClosesReaderWithoutWritingAndPageFailureDoesNotAbortOtherPages() = runBlocking {
        val dir=temp.newFolder(); val pdf=temp.newFile(); val reader=Reader().apply { pause=true }
        val work=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val session=QuestionDetectionSession(pdf,"uri","Title",listOf(0,1),QuestionSetRepository(dir),work,{true},{reader})
            session.scan(listOf(0,1)); until { reader.started }; session.cancel()
            until { !session.busy && reader.closed }
            assertEquals(0,dir.listFiles()!!.size)
            val failing=Reader().apply { failPage=0 }
            val retry=QuestionDetectionSession(pdf,"uri","Title",listOf(0,1),QuestionSetRepository(dir),work,{true},{failing})
            retry.scan(listOf(0,1)); until { !retry.busy }
            assertEquals(1,retry.proposals.size); assertEquals(1,retry.proposals.single().sourcePageIndex)
            assertTrue(retry.errors.single().startsWith("Page 1:"))
            retry.cancel(); assertEquals(0,dir.listFiles()!!.size)
        } finally { work.cancel() }
    }
    @Test fun staleNotebookCannotCommitAndInvalidRangeDoesNotOpenReader() = runBlocking {
        val dir=temp.newFolder(); val work=CoroutineScope(coroutineContext+SupervisorJob()); val reader=Reader()
        try {
            val session=QuestionDetectionSession(temp.newFile(),"uri","Title",listOf(2),QuestionSetRepository(dir),work,{false},{reader})
            session.scan(listOf(0)); assertFalse(session.busy); assertFalse(reader.started)
            session.scan(listOf(2)); until { !session.busy }; session.acceptAll()
            session.commit { fail("Stale notebook committed") }
            assertTrue(session.status.contains("Notebook changed")); assertEquals(0,dir.listFiles()!!.size)
        } finally { work.cancel() }
    }
}
