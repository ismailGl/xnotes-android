package com.xnotes.core.model

import com.xnotes.core.geometry.*
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.TextMeasurer

/** Materialized, read-only view of one scratch workspace. Replaced by owner, never independently edited. */
class QuestionInkProjection(val owner: String, val clip: Rect, val ink: List<CanvasItem>) : CanvasItem {
    override val kind = "question_projection"
    override val resizable = false
    override var locked: Boolean
        get() = true
        set(value) {} // Scratch history is the sole editing authority.
    override fun paint(r: Renderer) {
        r.save()
        try { r.clipRect(clip); ink.forEach { it.paint(r) } } finally { r.restore() }
    }
    override fun bounds() = clip
    override fun contains(p: Pt) = false
    override fun centroid() = clip.center
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double) = false
    override fun translate(dx: Double, dy: Double) {}
    override fun applyTransform(t: Affine) {}
    override fun snapshotGeometry(): GeometrySnapshot = ProjectionSnapshot
    override fun restoreGeometry(snap: GeometrySnapshot) {}
}
private data object ProjectionSnapshot : GeometrySnapshot

object QuestionInkProjector {
    /** Coordinates are document-space, never viewport pixels. PDF rotation is already baked into
     * the upright page dimensions used by both PdfImporter and QuestionPdfRenderer. Quarter turns
     * here describe the displayed image's orientation within the scratch workspace. */
    fun mapping(window: Rect, source: Rect, quarterTurns: Int = 0): Affine {
        require(window.w > 0 && window.h > 0 && source.w > 0 && source.h > 0)
        val normalized = Affine(1/window.w,0.0,0.0,1/window.h,-window.x/window.w,-window.y/window.h)
        val unrotate = when ((quarterTurns % 4 + 4) % 4) {
            1 -> Affine(0.0,-1.0,1.0,0.0,0.0,1.0)
            2 -> Affine(-1.0,0.0,0.0,-1.0,1.0,1.0)
            3 -> Affine(0.0,1.0,-1.0,0.0,1.0,0.0)
            else -> Affine.IDENTITY
        }
        return Affine(source.w,0.0,0.0,source.h,source.x,source.y).compose(unrotate).compose(normalized)
    }
    fun replace(notebook: Document, owner: String, pageIndex: Int, window: Rect, source: Rect,
                items: List<CanvasItem>, measurer: TextMeasurer, quarterTurns: Int = 0) {
        val page = requireNotNull(notebook.pages.firstOrNull { it.pdfPage == pageIndex })
        val transform = mapping(window,source,quarterTurns)
        val ink = items.filter { (it is Stroke || it is ShapeItem) && it.paintBounds().intersects(window) }
            .map { it.deepCopy(measurer).apply { applyTransform(transform) } }
        remove(notebook,owner)
        if (ink.isNotEmpty()) page.items.add(QuestionInkProjection(owner,source,ink))
        notebook.dirty = true
    }
    fun remove(notebook: Document, owner: String) {
        for (page in notebook.pages) if (page.items.removeAll { it is QuestionInkProjection && it.owner == owner }) notebook.dirty=true
    }
}

/** Read-only source layer. The active workspace already paints its own strokes above this layer. */
object QuestionSourceInk {
    fun paint(renderer: Renderer, page: Page, crop: Rect, activeOwner: String) {
        renderer.save()
        try {
            renderer.clipRect(crop)
            page.items.filter { it.paintBounds().intersects(crop) &&
                !(it is QuestionInkProjection && it.owner == activeOwner) }.forEach { it.paint(renderer) }
        } finally { renderer.restore() }
    }
}
