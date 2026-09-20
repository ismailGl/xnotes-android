package com.xnotes.ui

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question

/** PDF-page-local normalized hit testing; the caller maps viewport points through CanvasState. */
object QuestionOverlayGeometry {
    fun capturesDown(enabled: Boolean, editing: Boolean, hit: Boolean, stylus: Boolean): Boolean =
        enabled && hit && (editing || !stylus)
    fun hit(questions: List<Question>, page: Int, x: Double, y: Double): Question? =
        questions.lastOrNull { it.sourcePageIndex == page && x in it.crop.left..it.crop.right && y in it.crop.top..it.crop.bottom }

    fun corner(crop: NormalizedRect, x: Double, y: Double, toleranceX: Double, toleranceY: Double): Int =
        listOf(crop.left to crop.top, crop.right to crop.top, crop.right to crop.bottom, crop.left to crop.bottom)
            .indexOfFirst { (cx, cy) -> kotlin.math.abs(cx - x) <= toleranceX && kotlin.math.abs(cy - y) <= toleranceY }
}
