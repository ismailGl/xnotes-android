package com.xnotes.canvas

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.NormalizedRect

object QuestionCrop {
    data class Selection(val sourcePageIndex: Int, val crop: NormalizedRect)

    /** Reject gaps/cross-page selections; extra paper margins on the same page are clipped away. */
    fun fromSelection(state: CanvasState, content: Rect): Selection {
        require(listOf(content.x, content.y, content.w, content.h).all { it.isFinite() } &&
            content.w >= InteractionController.SHOT_MIN && content.h >= InteractionController.SHOT_MIN) {
            "Selection is too small"
        }
        val hits = state.pageRects.indices.filter { i ->
            val r = state.pageRects[i]
            content.left < r.right && content.right > r.left && content.top < r.bottom && content.bottom > r.top
        }
        require(hits.size == 1) { "Select a region within one PDF-backed page; selections cannot cross page boundaries" }
        val i = hits.single()
        val footprint = state.pageRects[i]
        val tolerance = 1e-6
        require(content.left >= footprint.left - tolerance && content.top >= footprint.top - tolerance &&
            content.right <= footprint.right + tolerance && content.bottom <= footprint.bottom + tolerance) {
            "Selection crosses page boundaries"
        }
        val page = state.document.pages[i]
        val pdfPage = requireNotNull(page.pdfPage) { "Page is not PDF-backed" }
        require(pdfPage >= 0) { "Invalid source PDF page" }
        require(state.document.hasPdf) { "Page is not PDF-backed" }
        require(page.width > 0 && page.height > 0) { "Invalid page size" }
        val local = state.toPageSpaceRect(i, content)
        val left = local.left.coerceIn(0.0, page.width)
        val top = local.top.coerceIn(0.0, page.height)
        val right = local.right.coerceIn(0.0, page.width)
        val bottom = local.bottom.coerceIn(0.0, page.height)
        require(right - left >= InteractionController.SHOT_MIN && bottom - top >= InteractionController.SHOT_MIN) {
            "Selection is too small or outside the PDF content"
        }
        return Selection(pdfPage, NormalizedRect(left / page.width, top / page.height,
            right / page.width, bottom / page.height))
    }
}
