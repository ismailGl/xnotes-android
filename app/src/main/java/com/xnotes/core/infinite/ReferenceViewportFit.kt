package com.xnotes.core.infinite

import com.xnotes.core.geometry.Rect

/** Tracks whether a reference is still in its automatic fit, leaving a manually moved view alone. */
class ReferenceViewportFit {
    private data class Pose(val zoom: Double, val x: Double, val y: Double)
    private var fitted: Pose? = null
    private var pending = false

    fun fit(viewport: CanvasViewport, bounds: Rect) {
        if (viewport.widthPx <= 0 || viewport.heightPx <= 0) { pending = true; return }
        viewport.fit(bounds)
        fitted = Pose(viewport.zoom, viewport.scrollX, viewport.scrollY)
        pending = false
    }

    /** Called after the view updates its measured width and height. */
    fun refitIfStillAutomatic(viewport: CanvasViewport, bounds: Rect): Boolean {
        if (pending) { fit(viewport, bounds); return !pending }
        val last = fitted ?: return false
        if (last != Pose(viewport.zoom, viewport.scrollX, viewport.scrollY)) return false
        fit(viewport, bounds)
        return true
    }

    fun clear() { fitted = null; pending = false }
}
