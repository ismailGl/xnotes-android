package com.xnotes.core.model

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.pal.TextMeasurer

/** Keep PDF page coordinates, so correcting any edge of a crop never moves existing ink. */
object QuestionCanvasMigration {
    fun anchor(question: Question, width: Double, height: Double) = Rect.ltrb(
        question.crop.left * width, question.crop.top * height,
        question.crop.right * width, question.crop.bottom * height)

    fun migrate(notebook: Document, question: Question, measurer: TextMeasurer, legacy: Document? = null): InfiniteDocument {
        val page = notebook.pages.firstOrNull { it.pdfPage == question.sourcePageIndex }
        val canvas = InfiniteDocument(dpi = notebook.dpi)
        val crop = page?.let { anchor(question, it.width, it.height) }
        // Source ink remains owned by the notebook; never import it as new scratch ink.
        // Preserve older separate answer sheets as additional workspace below the question.
        var y = (crop?.bottom ?: 0.0) + 80.0
        legacy?.pages?.forEach { sheet ->
            canvas.addAll(sheet.items.map { it.deepCopy(measurer).apply { translate(crop?.left ?: 0.0, y) } })
            y += sheet.height + 80.0
        }
        return canvas
    }
}
