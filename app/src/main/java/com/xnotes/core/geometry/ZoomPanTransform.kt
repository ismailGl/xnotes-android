package com.xnotes.core.geometry

import kotlin.math.max
import kotlin.math.min

/** Uniform fit-inside transform. Pan is measured in viewport pixels relative to the center. */
data class ZoomPanTransform(
    val contentWidth: Double,
    val contentHeight: Double,
    val viewportWidth: Double,
    val viewportHeight: Double,
    val zoom: Double = 1.0,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
) {
    init {
        require(listOf(contentWidth, contentHeight, viewportWidth, viewportHeight).all { it.isFinite() && it > 0 })
        require(zoom.isFinite() && zoom in 1.0..MAX_ZOOM && panX.isFinite() && panY.isFinite())
    }
    val fitScale get() = min(viewportWidth / contentWidth, viewportHeight / contentHeight)
    val scale get() = fitScale * zoom
    val left get() = (viewportWidth - contentWidth * scale) / 2 + panX
    val top get() = (viewportHeight - contentHeight * scale) / 2 + panY

    fun reset() = copy(zoom = 1.0, panX = 0.0, panY = 0.0)

    /** Native pinch detectors report a moving focus as well as a change in span. */
    fun pinch(previousFocus: Pt, focus: Pt, zoomFactor: Double) =
        gesture(previousFocus, Pt(focus.x - previousFocus.x, focus.y - previousFocus.y), zoomFactor)

    /** Keep the content under the pinch centroid fixed, then apply pan and constrain the edges. */
    fun gesture(centroid: Pt, pan: Pt, zoomFactor: Double): ZoomPanTransform {
        if (!listOf(centroid.x, centroid.y, pan.x, pan.y, zoomFactor).all { it.isFinite() } || zoomFactor <= 0) return this
        val nextZoom = (zoom * zoomFactor).coerceIn(1.0, MAX_ZOOM)
        if (nextZoom == 1.0) return reset()
        val ratio = nextZoom / zoom
        val limitX = max(0.0, (contentWidth * fitScale * nextZoom - viewportWidth) / 2)
        val limitY = max(0.0, (contentHeight * fitScale * nextZoom - viewportHeight) / 2)
        return copy(zoom = nextZoom,
            panX = if (limitX == 0.0) 0.0 else (panX * ratio + (centroid.x - viewportWidth / 2) * (1 - ratio) + pan.x).coerceIn(-limitX, limitX),
            panY = if (limitY == 0.0) 0.0 else (panY * ratio + (centroid.y - viewportHeight / 2) * (1 - ratio) + pan.y).coerceIn(-limitY, limitY))
    }

    fun visibleRect() = if (zoom == 1.0) Rect(0.0, 0.0, contentWidth, contentHeight) else Rect.ltrb(
        (-left / scale).coerceIn(0.0, contentWidth),
        (-top / scale).coerceIn(0.0, contentHeight),
        ((viewportWidth - left) / scale).coerceIn(0.0, contentWidth),
        ((viewportHeight - top) / scale).coerceIn(0.0, contentHeight),
    )

    companion object { const val MAX_ZOOM = 5.0 }
}
