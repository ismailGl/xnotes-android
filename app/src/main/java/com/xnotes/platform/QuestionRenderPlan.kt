package com.xnotes.platform

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.geometry.Rect
import com.xnotes.core.geometry.ZoomPanTransform
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A bounded visible PDF region and its destination in unscaled question-local coordinates. */
data class QuestionRenderPlan(
    val pageIndex: Int,
    val fullWidth: Int,
    val fullHeight: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val contentRect: Rect,
) {
    companion object {
        fun create(pageIndex: Int, pageWidth: Int, pageHeight: Int, crop: NormalizedRect,
                   transform: ZoomPanTransform): QuestionRenderPlan {
            require(pageIndex >= 0 && pageWidth > 0 && pageHeight > 0) { "Invalid PDF page size" }
            val visible = transform.visibleRect()
            val pixelWidth = visible.w * transform.scale
            val pixelHeight = visible.h * transform.scale
            require(pixelWidth > 0 && pixelHeight > 0) { "Invalid question crop size" }
            // Display resolution at current zoom, with rounding headroom for PdfSource's 4096px
            // region limit. Only unusually large windows are reduced to keep a bitmap near 32MB.
            val renderScale = transform.scale * minOf(1.0, 4093.0 / pixelWidth, 4093.0 / pixelHeight,
                sqrt(8_000_000.0 / (pixelWidth * pixelHeight)))
            val fw = ceil(pageWidth * renderScale)
            val fh = fw * pageHeight / pageWidth
            // Guard integer overflow and float matrix precision for corrupt/extremely narrow crops.
            require(fw.isFinite() && fh.isFinite() && fw in 1.0..16_000_000.0 && fh in 1.0..16_000_000.0) {
                "Question crop is too narrow to render safely"
            }
            val fullWidth = fw.toInt()
            val fullHeight = fh.roundToInt()
            val sx = fullWidth.toDouble() / pageWidth
            val sy = fullHeight.toDouble() / pageHeight
            val cropX = crop.left * pageWidth
            val cropY = crop.top * pageHeight
            val left = floor((cropX + visible.left) * sx).toInt().coerceIn(0, fullWidth)
            val top = floor((cropY + visible.top) * sy).toInt().coerceIn(0, fullHeight)
            val right = ceil((cropX + visible.right) * sx).toInt().coerceIn(0, fullWidth)
            val bottom = ceil((cropY + visible.bottom) * sy).toInt().coerceIn(0, fullHeight)
            require(right - left in 1..4096 && bottom - top in 1..4096) { "Invalid question crop size" }
            return QuestionRenderPlan(pageIndex, fullWidth, fullHeight, left, top, right - left, bottom - top,
                Rect.ltrb(left / sx - cropX, top / sy - cropY, right / sx - cropX, bottom / sy - cropY))
        }
    }
}
