package com.xnotes.ui

import android.content.Context
import android.graphics.Bitmap
import com.xnotes.core.geometry.ZoomPanTransform
import com.xnotes.core.infinite.ReferenceViewportFit
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
    private val imageDir: File, private val onProjectionChanged: () -> Unit,
    private val persistSource: suspend () -> Unit, private val onError: (String) -> Unit,
    private val readOnly: Boolean = false,
) {
    private val codec = CanvasCodec(AndroidImageCodec())
    private val renderer = QuestionPdfRenderer(context, requireNotNull(notebook.pdfFile))
    private val measurer = AndroidTextMeasurer()
    private val scope = MainScope()
    private val writes = Mutex()
    private var debounce: Job? = null
    private var id: String? = null
    private val retained = linkedMapOf<String, Pair<InfiniteDocument, History>>()
    private val references = linkedMapOf<String, Pair<Question, ImageItem>>()
    private var prefetchJob: Job? = null
    private var prefetchId: String? = null
    private var deleting = false
    private var scratchDirty = false
    private var activeQuestion: Question? = null
    private var reference: ImageItem? = null
    private val referenceFit = ReferenceViewportFit()
    private var sourceRefresh: Job? = null
    private var sourceRevision = 0
    private var visibleSourceItems: List<CanvasItem> = emptyList()
    private val scratch = QuestionScratchStore(files, setId, codec, imageDir)

    init {
        editor.sourceEraseTarget = sourceErase@{
            if (readOnly) return@sourceErase null
            val question = activeQuestion
            val image = reference
            val page = question?.let { q -> notebook.pages.firstOrNull { it.pdfPage == q.sourcePageIndex } }
            if (question == null || image == null || page == null || deleting) null else
                QuestionSourceEraser(page, image.rect, QuestionCanvasMigration.anchor(question, page.width, page.height),
                    image.orientation, ::sourceChanged)
        }
        editor.onContentChanged = contentChanged@{
            if (readOnly) return@contentChanged
            scratchDirty = true
            project()
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
        val started = android.os.SystemClock.elapsedRealtime()
        editor.finishInput()
        if (prefetchId == question?.id) prefetchJob?.join() else prefetchJob?.cancelAndJoin()
        prefetchId = null
        sourceRefresh?.cancelAndJoin()
        editor.inputEnabled = false
        try {
            if (!deleting) id?.let { retained[it] = editor.document to editor.history }
            if (question == null) {
                id = null
                activeQuestion = null; reference = null
                referenceFit.clear()
                editor.setReferenceItems(emptyList())
                editor.replaceDocument(InfiniteDocument())
                return
            }
            require(question.id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            val reference = referenceFor(question)
            val cached = retained.remove(question.id)
            val doc = cached?.first ?: withContext(Dispatchers.IO) {
                scratch.load(question.id)
            } ?: run {
                val legacy = withContext(Dispatchers.IO) {
                    files.read("$setId/answers/${question.id}.xnote")?.inputStream()?.use {
                        DocumentCodec(AndroidImageCodec(), measurer).read(it, imageDir = imageDir)
                    }
                }
                QuestionCanvasMigration.migrate(notebook, question, measurer, legacy).also { migrated ->
                    if (!readOnly) withContext(Dispatchers.IO) { scratch.save(question.id, migrated) }
                }
            }
            id = question.id
            scratchDirty = false
            activeQuestion = question
            visibleSourceItems = sourceItems()
            this.reference = reference
            deleting = false
            editor.setReferenceItems(listOf(reference))
            editor.replaceDocument(doc, cached?.second ?: History())
            fitCurrentQuestion()
            val hasProjection = notebook.pages.firstOrNull { it.pdfPage == question.sourcePageIndex }?.items
                ?.any { it is QuestionInkProjection && it.owner == "$setId:${question.id}" } == true
            if (!readOnly && !hasProjection && doc.items.any { it is Stroke || it is ShapeItem }) project()
            while (retained.size > 3) retained.remove(retained.keys.first())
        } finally {
            editor.inputEnabled = true
            if (com.xnotes.BuildConfig.DEBUG) android.util.Log.d("xnotes.questions",
                "open ${question?.id}: ${android.os.SystemClock.elapsedRealtime() - started}ms")
        }
    }

    suspend fun updateCrop(question: Question) {
        if (id == question.id) {
            sourceRefresh?.cancelAndJoin()
            activeQuestion = question
            references.remove(question.id)
            reference = referenceFor(question)
            editor.setReferenceItems(listOf(requireNotNull(reference)))
            fitCurrentQuestion()
            project(); save()
        }
    }

    fun fitCurrentQuestion() {
        reference?.let { editor.fitReference(it.rect, referenceFit) }
    }

    fun onViewportResized(): Boolean = reference?.let {
        editor.refitReferenceOnResize(it.rect, referenceFit)
    } ?: false

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
        // Display-only composition: source items are never copied into editable scratch ink.
        if (page != null) {
            val inkRenderer = AndroidRenderer(android.graphics.Canvas(bitmap))
            inkRenderer.save()
            inkRenderer.scale(bitmap.width / anchor.w, bitmap.height / anchor.h)
            inkRenderer.translate(-anchor.x, -anchor.y)
            QuestionSourceInk.paint(inkRenderer, page, anchor, "$setId:${question.id}")
            inkRenderer.restore()
        }
        val data = withContext(Dispatchers.IO) {
            val file = File.createTempFile("question-crop-", ".png", imageDir)
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                ImageData(file, bitmap.width, bitmap.height)
            } finally { bitmap.recycle() }
        }
        return ImageItem(data, anchor).apply { locked = true }
    }

    private fun project() {
        if (deleting) return
        val question = activeQuestion ?: return
        val image = reference ?: return
        val page = notebook.pages.firstOrNull { it.pdfPage == question.sourcePageIndex } ?: return
        QuestionInkProjector.replace(notebook,"$setId:${question.id}",question.sourcePageIndex,
            image.rect,QuestionCanvasMigration.anchor(question,page.width,page.height),
            editor.document.items,measurer,image.orientation)
        onProjectionChanged()
    }

    private suspend fun referenceFor(question: Question): ImageItem {
        references[question.id]?.takeIf { it.first == question }?.let { return it.second }
        val rendered = image(question)
        references[question.id] = question to rendered
        while (references.size > 4) references.remove(references.keys.first())
        return rendered
    }

    fun prefetch(question: Question?) {
        if (question == null || question.id in references || deleting) return
        prefetchJob?.cancel()
        prefetchId = question.id
        prefetchJob = scope.launch {
            try { referenceFor(question) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Navigation renders on demand if prefetch fails. */ }
        }
    }

    /** Recompose only source mutations; scratch drawing keeps its existing projection path. */
    private fun sourceItems(): List<CanvasItem> {
        val question = activeQuestion ?: return emptyList()
        return notebook.pages.firstOrNull { it.pdfPage == question.sourcePageIndex }?.items.orEmpty()
            .filterNot { it is QuestionInkProjection && it.owner == "$setId:${question.id}" }
    }

    /** The normal editor's mutation/undo path also invalidates this display-only composition. */
    fun refreshSourceInk() {
        if (activeQuestion != null && !deleting && sourceItems() != visibleSourceItems) sourceChanged()
    }

    private fun sourceChanged() {
        visibleSourceItems = sourceItems()
        prefetchJob?.cancel()
        references.clear() // adjacent crops may contain the same changed notebook ink
        notebook.dirty = true
        onProjectionChanged()
        sourceRevision++
        if (sourceRefresh?.isActive == true) return
        sourceRefresh = scope.launch {
            try {
                do {
                    val revision = sourceRevision
                    val question = activeQuestion ?: return@launch
                    val refreshed = image(question)
                    if (activeQuestion?.id != question.id) return@launch
                    if (revision == sourceRevision) {
                        reference = refreshed
                        references[question.id] = question to refreshed
                        editor.setReferenceItems(listOf(refreshed))
                    }
                } while (revision != sourceRevision)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { onError("Could not refresh question source ink") }
        }
    }

    suspend fun save() = writes.withLock {
        if (readOnly) return@withLock
        if (deleting) return@withLock
        val current = id ?: return@withLock
        if (scratchDirty) {
            val snapshot = editor.document.snapshotForWrite()
            withContext(Dispatchers.IO) { scratch.save(current, snapshot) }
            scratchDirty = false
        }
        persistSource()
    }

    suspend fun prepareTransition() {
        val started = android.os.SystemClock.elapsedRealtime()
        editor.finishInput()
        debounce?.cancelAndJoin()
        save()
        if (com.xnotes.BuildConfig.DEBUG) android.util.Log.d("xnotes.questions",
            "save before navigation: ${android.os.SystemClock.elapsedRealtime() - started}ms")
    }

    suspend fun beginDelete() {
        editor.finishInput()
        debounce?.cancelAndJoin()
        writes.withLock {
            deleting = true
            id?.let { retained.remove(it); QuestionInkProjector.remove(notebook,"$setId:$it") }
            onProjectionChanged(); persistSource()
        }
    }

    fun background() {
        editor.finishInput()
        scope.launch { try { save() } catch (e: CancellationException) { throw e } catch (_: Exception) { onError("Could not save question ink") } }
    }

    suspend fun close() {
        prefetchJob?.cancelAndJoin()
        sourceRefresh?.cancelAndJoin()
        debounce?.cancelAndJoin()
        editor.onContentChanged = null
        editor.sourceEraseTarget = null
        editor.setReferenceItems(emptyList())
        retained.clear()
        references.clear()
        renderer.close()
        scope.cancel()
    }
}
