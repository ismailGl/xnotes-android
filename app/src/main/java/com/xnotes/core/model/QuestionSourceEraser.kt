package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.PageEraseSession
import com.xnotes.core.infinite.EraseTarget

/** Routes content-space eraser samples to the real page, not its display-only projection. */
class QuestionSourceEraser(private val page: Page, private val window: Rect, private val crop: Rect,
    quarterTurns: Int = 0, private val changed: () -> Unit = {},
) : EraseTarget {
    private val mapping = QuestionInkProjector.mapping(window, crop, quarterTurns)
    private val session = PageEraseSession()

    override fun erase(cx: Double, cy: Double, radius: Double, area: Boolean): Rect? {
        if (!window.contains(Pt(cx, cy))) return null
        val p = mapping.apply(Pt(cx, cy))
        // Contain the entire source hit-test circle, including near a crop edge. Workspace-only
        // samples never reach the source even when a large cursor overlaps the question.
        val safeRadius = minOf(radius * minOf(mapping.scaleX, mapping.scaleY),
            p.x - crop.left, crop.right - p.x, p.y - crop.top, crop.bottom - p.y)
        if (safeRadius <= 0.0) return null
        // Locked QuestionInkProjections are skipped by the normal page eraser. Active scratch
        // ink is erased once by EraseSession, then its existing projector publishes the result.
        session.erase(page, p.x, p.y, safeRadius, area) ?: return null
        changed()
        return window // EraseTarget reports damage in workspace coordinates.
    }

    override fun buildCommand(): com.xnotes.core.history.Command? {
        val command = session.buildCommand() ?: return null
        return object : com.xnotes.core.history.Command by command {
            override fun undo() { command.undo(); changed() }
            override fun redo() { command.redo(); changed() }
        }
    }
}
