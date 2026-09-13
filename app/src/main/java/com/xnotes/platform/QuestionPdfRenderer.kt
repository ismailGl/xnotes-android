package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import com.xnotes.core.model.Question
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Viewer-owned PDF handle. Serialized IO, no notebook renderer or bitmap cache is shared. */
class QuestionPdfRenderer(context: Context, private val file: File) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var source: PdfSource? = null
    private var closed = false

    private suspend fun <T> withSource(block: (PdfSource) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Question viewer is closed" }
            val pdf = source ?: (PdfSource.create(context, file)
                ?: error("The source PDF is inaccessible or could not be opened")).also { source = it }
            try { block(pdf) } catch (e: Exception) {
                // A failed framework render may leave its page open. Reopen on the next request.
                runCatching { pdf.close() }
                source = null
                throw e
            }
        }
    }

    suspend fun pageSize(question: Question): Pair<Int, Int> = withSource { pdf ->
        require(question.sourcePageIndex in 0 until pdf.pageCount) { "This question refers to an invalid PDF page" }
        pdf.pageSizePoints(question.sourcePageIndex) ?: error("Could not read the PDF page")
    }

    suspend fun render(plan: QuestionRenderPlan): Bitmap = withSource { pdf ->
        pdf.renderRegion(plan.pageIndex, plan.fullWidth, plan.fullHeight, plan.left, plan.top,
            plan.width, plan.height)?.bitmap ?: error("Could not render this question")
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            closed = true
            source?.let { runCatching { it.close() } }
            source = null
        }
    }
}
