package com.xnotes.core.model

import kotlin.math.abs

/** All inputs use the upright, top-left source-page frame. No editor or OCR state. */
object QuestionLayoutDetector {
    data class TextRun(val text: String, val box: NormalizedRect)
    data class Layout(val width: Int, val height: Int, val ink: BooleanArray) {
        init { require(width > 0 && height > 0 && ink.size == width * height) }
        fun occupied(x: Int, y: Int) = ink[y * width + x]
    }
    private val anchor = Regex("^(?:[0-9]{1,3}[.)](?:\\s|$)|[Ss][Oo][Rr][Uu]\\s+[0-9]{1,3}(?:[.):]|\\s|$))")

    fun detect(page: Int, runs: List<TextRun>, layout: Layout): List<DetectedQuestion> {
        // Join adjacent glyphs on a baseline, but never bridge a column-sized gap.
        val lines = mutableListOf<TextRun>()
        for (run in runs.sortedBy { it.box.top }) {
            val i = lines.indexOfLast { abs(it.box.bottom - run.box.bottom) < 0.006 &&
                run.box.left >= it.box.right - 0.003 && run.box.left - it.box.right < 0.012 }
            if (i < 0) lines += run else {
                val old = lines[i]
                lines[i] = TextRun(old.text + (if (run.box.left - old.box.right > 0.002) " " else "") + run.text,
                    NormalizedRect(old.box.left, minOf(old.box.top, run.box.top), run.box.right, maxOf(old.box.bottom, run.box.bottom)))
            }
        }
        val anchors = lines.filter { anchor.containsMatchIn(it.text.trimStart()) && it.box.top in 0.025..0.95 }
        if (anchors.isEmpty()) return emptyList()
        // Repeated left edges suggest column starts. Pick a gutter from actual whitespace,
        // and require anchors on both sides; no fixed halfway split.
        val starts = anchors.map { it.box.left }.sorted()
        val gap = starts.zipWithNext().filter { it.second - it.first > 0.18 }.maxByOrNull { it.second - it.first }
        val gutter = gap?.let { (left, right) ->
            val from = ((left + 0.12) * layout.width).toInt()
            val to = ((right - 0.015) * layout.width).toInt()
            val scores = (from..to).map { x ->
                (layout.height / 12 until layout.height * 11 / 12).count { y -> layout.occupied(x.coerceIn(0, layout.width - 1), y) } +
                    lines.count { x.toDouble()/layout.width in it.box.left..it.box.right } * 12
            }
            val minimum = scores.minOrNull() ?: return@let null
            var start = 0; var bestStart = 0; var bestLength = 0
            scores.forEachIndexed { i, value ->
                if (value > minimum + 2) start = i + 1
                else if (i - start + 1 > bestLength) { bestStart = start; bestLength = i - start + 1 }
            }
            if (bestLength < layout.width * 0.012) null else (from + bestStart + bestLength / 2.0) / layout.width
        }
        val edges = if (gutter == null) listOf(0.0, 1.0) else listOf(0.0, gutter, 1.0)
        val output = mutableListOf<DetectedQuestion>()
        for (column in 0 until edges.lastIndex) {
            val left = edges[column]; val right = edges[column + 1]
            val group = anchors.filter { it.box.left >= left && it.box.left < right }.sortedBy { it.box.top }
            group.forEachIndexed { i, start ->
                val next = group.getOrNull(i + 1)
                val top = (start.box.top - 0.006).coerceAtLeast(0.0)
                val limit = (next?.box?.top?.minus(0.006) ?: 0.97).coerceAtLeast(start.box.bottom)
                // Keep the entire band, including non-text ink and open-ended writing space.
                // Use pixel occupancy to avoid cutting through a diagram at a proposed boundary.
                val y = (limit * layout.height).toInt().coerceIn(0, layout.height - 1)
                val boundaryInk = ((left * layout.width).toInt() until (right * layout.width).toInt()).count { x -> layout.occupied(x, y) }
                val reasons = mutableListOf<String>()
                if (next == null) reasons += "Last question in column: check for continuation and footer"
                if (boundaryInk > 3) reasons += "Content touches the lower boundary; continuation may be clipped"
                if (lines.any { it.box.top >= top && it.box.top < limit && it.box.left < right && it.box.right > right + 0.005 })
                    reasons += "Content crosses a column boundary"
                if (gutter != null) {
                    val gx = (gutter * layout.width).toInt().coerceIn(0,layout.width-1)
                    if (((top*layout.height).toInt() until (limit*layout.height).toInt()).count { yy -> layout.occupied(gx,yy) } > 3)
                        reasons += "Diagram or layout ink touches the gutter; check the crop width"
                }
                if (gutter != null && (column == 0 || i == 0)) reasons += "Check column split and any shared passage/continuation"
                if (start.box.top - (group.getOrNull(i - 1)?.box?.bottom ?: 0.0) < 0.012)
                    reasons += "Closely spaced numbering; check subparts"
                output += DetectedQuestion("$page-$column-$i", page, NormalizedRect(left, top, right, limit), reasons)
            }
        }
        return output
    }
}
