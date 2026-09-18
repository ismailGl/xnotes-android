package com.xnotes.ui

import android.content.Context
import android.graphics.Bitmap
import com.xnotes.core.geometry.ZoomPanTransform
import com.xnotes.core.history.History
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.*
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistence and PDF reference content only. All editing belongs to the existing xCanvas editor. */
class QuestionCanvasWorkspace(context: Context, val editor: InfiniteEditor,
    private val notebook: Document, private val setId: String, private val files: QuestionFiles,
    private val imageDir: File, private val onError: (String) -> Unit,
) {
    private val codec = CanvasCodec(AndroidImageCodec())
    private val renderer = QuestionPdfRenderer(context, requireNotNull(notebook.pdfFile))
    private val measurer = AndroidTextMeasurer()
    private val scope = MainScope()
    private val writes = Mutex()
    private var debounce: Job? = null
    private var id: String? = null
    private val retained = linkedMapOf<String, Pair<InfiniteDocument, History>>()
    private var deleting = false
    private fun path(id: String) = "$setId/questions/$id/ink.xcanvas"

    init {
        editor.onContentChanged = {
            debounce?.cancel()
            debounce = scope.launch {
                delay(700)
                scope.launch {
                try { save() } catch (e: CancellationException) { throw e }
                catch (_: Exception) { onError("Could not save question ink. Retry before leaving.") }
                }
            }
        }
    }

    suspend fun open(question: Question?) {
        editor.finishInput()
        editor.inputEnabled = false
        try {
            if (!deleting) id?.let { retained[it] = editor.document to editor.history }
            if (question == null) {
                id = null
                editor.setReferenceItems(emptyList())
                editor.replaceDocument(InfiniteDocument())
                return
            }
            require(question.id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val reference = image(question)
            val cached = retained.remove(question.id)
            val doc = cached?.first ?: withContext(Dispatchers.IO) {
                files.read(path(question.id))?.inputStream()?.use { codec.read(it, imageDir) }
            } ?: run {
                val legacy = withContext(Dispatchers.IO) {
                    files.read("$setId/answers/${question.id}.xnote")?.inputStream()?.use {
                        DocumentCodec(AndroidImageCodec(), measurer).read(it, imageDir = imageDir)
                    }
                }
                QuestionCanvasMigration.migrate(notebook, question, measurer, legacy).also { migrated ->
                    withContext(Dispatchers.IO) { files.write(path(question.id)) { codec.write(migrated, it) } }
                }
            }
            id = question.id
            deleting = false
            editor.setReferenceItems(listOf(reference))
            editor.replaceDocument(doc, cached?.second ?: History())
            while (retained.size > 3) retained.remove(retained.keys.first())
        } finally { editor.inputEnabled = true }
    }

    suspend fun updateCrop(question: Question) {
        if (id == question.id) editor.setReferenceItems(listOf(image(question)))
    }

    private suspend fun image(question: Question): ImageItem {
        val size = renderer.pageSize(question)
        val page = notebook.pages.firstOrNull { it.pdfPage == question.sourcePageIndex }
        val anchor = QuestionCanvasMigration.anchor(question, page?.width ?: size.first / 72.0 * notebook.dpi,
            page?.height ?: size.second / 72.0 * notebook.dpi)
        val w = (question.crop.right - question.crop.left) * size.first
        val h = (question.crop.bottom - question.crop.top) * size.second
        val scale = 2048.0 / maxOf(w, h)
        val plan = QuestionRenderPlan.create(question.sourcePageIndex, size.first, size.second, question.crop,
            ZoomPanTransform(w, h, w * scale, h * scale))
        val bitmap = renderer.render(plan)
        val data = withContext(Dispatchers.IO) {
            val file = File.createTempFile("question-crop-", ".png", imageDir)
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                ImageData(file, bitmap.width, bitmap.height)
            } finally { bitmap.recycle() }
        }
        return ImageItem(data, anchor).apply { locked = true }
    }

    suspend fun save() = writes.withLock {
        if (deleting) return@withLock
        val current = id ?: return@withLock
        val snapshot = editor.document.snapshotForWrite()
        withContext(Dispatchers.IO) { files.write(path(current)) { codec.write(snapshot, it) } }
    }

    suspend fun prepareTransition() {
        editor.finishInput()
        debounce?.cancelAndJoin()
        save()
    }

    suspend fun beginDelete() {
        editor.finishInput()
        debounce?.cancelAndJoin()
        writes.withLock { deleting = true; id?.let(retained::remove) }
    }

    fun background() {
        editor.finishInput()
        scope.launch { try { save() } catch (e: CancellationException) { throw e } catch (_: Exception) { onError("Could not save question ink") } }
    }

    suspend fun close() {
        debounce?.cancelAndJoin()
        editor.onContentChanged = null
        editor.setReferenceItems(emptyList())
        retained.clear()
        renderer.close()
        scope.cancel()
    }
}
