package com.xnotes.ui

import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.snapshot
import com.xnotes.platform.AnswerStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QuestionAnswerSessionTest {
    private class Store : AnswerStore {
        val saved = mutableMapOf<String, Document>()
        val events = mutableListOf<String>()
        var failSave = false
        var failLoad: String? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(answerId: String): Document {
            events += "load:$answerId"
            if (answerId == failLoad) error("corrupt")
            return (saved[answerId]?.snapshot() ?: Document.blank()).also { it.displayName = answerId }
        }
        override suspend fun save(answerId: String, document: Document) {
            events += "save:$answerId"
            gate?.await()
            if (failSave) error("disk full")
            saved[answerId] = document
        }
    }
    private class Surface(override val document: Document, val changed: () -> Unit, val events: MutableList<String>) : AnswerSurface {
        override var inputEnabled = true
        var disposed = false
        override fun finishInput() { events += "finish:${document.displayName}" }
        override fun snapshot() = document.snapshot()
        override fun dispose() { disposed = true; events += "dispose:${document.displayName}" }
        fun edit() { document.pages.add(Page(10.0, 10.0)); changed() }
    }
    private fun CoroutineScope.host(store: Store, debounceMs: Long = 60_000) = QuestionAnswerSession(store,
        { doc, _, changed -> Surface(doc, changed, store.events) },
        CoroutineScope(coroutineContext + SupervisorJob()), debounceMs = debounceMs)
    private suspend fun idle(host: QuestionAnswerSession) = withTimeout(3000) { while (host.busy) yield() }
    private suspend fun until(test: () -> Boolean) = withTimeout(3000) { while (!test()) yield() }

    @Test fun debounceSavesLatestEditsWithoutNavigation() = runBlocking {
        val store = Store()
        val host = host(store, debounceMs = 20)
        host.switchTo("a"); idle(host)
        val a = host.surface as Surface
        a.edit()
        a.edit()
        until { store.saved["a"] != null }
        assertEquals(3, store.saved["a"]!!.pages.size)
        assertEquals(1, store.events.count { it == "save:a" })
        assertFalse(a.document.dirty)
        host.close {}; idle(host)
        assertEquals(1, store.events.count { it == "save:a" })
    }

    @Test fun outgoingSavePrecedesDisposalAndNextLoadAndRevisitingRestoresAnswer() = runBlocking {
        val store = Store()
        val host = host(store)
        host.switchTo("a"); idle(host)
        val a = host.surface as Surface
        a.edit()
        store.events.clear()
        host.switchTo("b"); idle(host)
        assertTrue(store.events.indexOf("finish:a") < store.events.indexOf("save:a"))
        assertTrue(store.events.indexOf("save:a") < store.events.indexOf("dispose:a"))
        assertTrue(store.events.indexOf("dispose:a") < store.events.indexOf("load:b"))
        assertTrue(a.disposed)
        assertEquals(1, host.surface!!.document.pages.size)
        host.switchTo("a"); idle(host)
        assertEquals(2, host.surface!!.document.pages.size)
        assertNotSame(a, host.surface)
        host.close {}; idle(host)
    }

    @Test fun failedSaveRetainsLiveInkAndBlocksSwitchAndBackUntilRetry() = runBlocking {
        val store = Store()
        val host = host(store)
        host.switchTo("a"); idle(host)
        val a = host.surface as Surface
        a.edit(); store.failSave = true
        var switched = false
        host.switchTo("b") { switched = true }; idle(host)
        assertFalse(switched)
        assertSame(a, host.surface)
        assertTrue(a.inputEnabled)
        assertFalse(a.disposed)
        var closed = false
        host.close { closed = true }; idle(host)
        assertFalse(closed)
        assertNotNull(host.error)
        store.failSave = false
        host.close { closed = true }; idle(host)
        assertTrue(closed)
        assertEquals(2, store.saved["a"]!!.pages.size)
    }

    @Test fun rapidNavigationIsIgnoredAndBackWaitsForTheOutstandingSave() = runBlocking {
        val store = Store()
        val host = host(store)
        host.switchTo("a"); idle(host)
        store.gate = CompletableDeferred()
        host.switchTo("b")
        until { "save:a" in store.events }
        host.switchTo("c")
        var closed = false
        host.close { closed = true }
        assertFalse(closed)
        store.gate!!.complete(Unit)
        until { closed }
        assertFalse("load:c" in store.events)
        assertTrue("load:b" in store.events)
        assertNotNull(store.saved["a"])
        assertNotNull(store.saved["b"])
    }

    @Test fun corruptAnswerCanBeSkippedWithoutWritingIt() = runBlocking {
        val store = Store().apply { failLoad = "bad" }
        val host = host(store)
        host.switchTo("bad"); idle(host)
        assertNull(host.surface)
        assertNotNull(host.error)
        host.switchTo("good"); idle(host)
        assertNotNull(host.surface)
        assertFalse("save:bad" in store.events)
        host.close {}; idle(host)
    }

    @Test fun editsDuringBackgroundWriteAreIncludedInTheFinalSave() = runBlocking {
        val store = Store()
        val host = host(store)
        host.switchTo("a"); idle(host)
        val a = host.surface as Surface
        a.edit()
        store.gate = CompletableDeferred()
        host.background()
        until { "save:a" in store.events }
        a.edit()
        store.gate!!.complete(Unit)
        host.close {}; idle(host)
        assertEquals(3, store.saved["a"]!!.pages.size)
        assertEquals(2, store.events.count { it == "save:a" })
    }
}
