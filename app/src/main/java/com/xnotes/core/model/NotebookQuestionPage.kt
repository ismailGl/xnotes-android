package com.xnotes.core.model

import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command

/** A view of the actual notebook page, never a copied list of its items. */
class NotebookQuestionPage(private val notebook: Document, question: Question) {
    val page: Page = notebook.pages.filter { it.pdfPage == question.sourcePageIndex }.let {
        require(it.size == 1) { "The question must match exactly one notebook PDF page" }
        it.single()
    }
    val crop = Rect.ltrb(question.crop.left * page.width, question.crop.top * page.height,
        question.crop.right * page.width, question.crop.bottom * page.height)
    val viewDocument = Document(mutableListOf(page), dpi = notebook.dpi,
        style = notebook.style, margins = notebook.margins)

    /** Keep global history chronological; never jump over another page or structural command. */
    fun canApply(command: Command?): Boolean {
        val touched = command?.touched { item -> notebook.pages.firstOrNull { p -> p.items.any { it === item } } }
        return touched != null && touched.isNotEmpty() && touched.all { it.first === page }
    }
}
