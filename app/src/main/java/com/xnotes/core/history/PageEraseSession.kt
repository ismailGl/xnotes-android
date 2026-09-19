package com.xnotes.core.history

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*

/** Normal page erasing, shared by paged input and a Question Mode source-page gesture. */
class PageEraseSession {
    private val removals = mutableListOf<Pair<Page, CanvasItem>>()
    private val snapshots = linkedMapOf<Page, List<CanvasItem>>()

    fun erase(page: Page, cx: Double, cy: Double, radius: Double, area: Boolean): Rect? {
        var dirty: Rect? = null
        var i = 0
        while (i < page.items.size) {
            val item = page.items[i]
            val fragments = if (item.locked || item is ImageItem || item is TextItem) null
            else if (area) when (item) {
                is Stroke -> item.erasedBy(cx, cy, radius)
                is ShapeItem -> item.erasedBy(cx, cy, radius)
                else -> null
            } else if (item.intersectsCircle(cx, cy, radius)) emptyList() else null
            if (fragments == null) { i++; continue }
            if (area) snapshots.getOrPut(page) { page.items.toList() }
            else removals.add(page to item)
            dirty = dirty?.union(item.paintBounds()) ?: item.paintBounds()
            page.items.removeAt(i)
            page.items.addAll(i, fragments)
            i += fragments.size
        }
        return dirty
    }

    fun buildCommand(): Command? {
        val commands = snapshots.mapNotNull { (page, before) ->
            page.items.toList().takeIf { it != before }?.let { ReplacePageItems(page, before, it) }
        }.toMutableList<Command>()
        if (removals.isNotEmpty()) commands.add(EraseItems(removals.toList()))
        return when (commands.size) { 0 -> null; 1 -> commands.single(); else -> CompositeCommand(commands) }
    }
}
