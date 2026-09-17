package com.xnotes.core.model

import kotlin.math.abs

/** All inputs use the upright, top-left source-page frame. No editor or OCR state. */
object QuestionLayoutDetector {
    data class TextRun(val text: String, val box: NormalizedRect)
    data class Layout(val width: Int, val height: Int, val ink: BooleanArray) {
        init { require(width > 0 && height > 0 && ink.size == width * height) }
        fun occupied(x: Int, y: Int) = ink[y * width + x]
    }
    private val anchor = Regex("^(?:[0-9]{1,3}\\s*[.)]|[Ss][Oo][Rr][Uu]\\s+[0-9]{1,3}(?:[.):]|\\s|$))")

    private val option = Regex("(?:^|\\s)[A-E]\\s*\\)")

    data class Gutter(val left: Double, val right: Double) { val center get() = (left + right) / 2 }
    data class ColumnBounds(val left: Double, val right: Double) {
        init { require(left >= 0 && right <= 1 && left < right) }
        /** Final intersection: padding/content unions must never escape the assigned column. */
        fun clamp(crop: NormalizedRect): NormalizedRect? {
            val l=maxOf(left,crop.left); val r=minOf(right,crop.right)
            return if (l < r) NormalizedRect(l,crop.top,r,crop.bottom) else null
        }
    }
    data class AnchorDebug(val text: String, val box: NormalizedRect, val column: Int?, val excluded: String? = null,
        val source: String = "OCR", val confidence: Double = 0.0, val reason: String = excluded ?: "")
    data class Diagnostics(val page: Int, val columnCount: Int, val gutter: Gutter?,
        val anchors: List<AnchorDebug>, val proposals: List<DetectedQuestion>,
        val columnBounds: List<ColumnBounds>,
        val regions: List<QuestionPageRegions.Region> = emptyList())

    fun detect(page: Int, runs: List<TextRun>, layout: Layout): List<DetectedQuestion> =
        analyze(page, runs, layout).proposals

    /** No bitmaps retained. Also callable by regression tests and the review diagnostics UI. */
    fun analyze(page: Int, runs: List<TextRun>, layout: Layout, textSource: QuestionTextSource = QuestionTextSource.OCR): Diagnostics {
        val regions = QuestionReadingBands.refine(QuestionPageRegions.classify(runs),runs,layout)
        val excluded = regions.filter { it.role != QuestionPageRegions.Role.QUESTIONS }
        if(excluded.isEmpty()) return analyzeColumns(page,runs,layout,textSource).copy(regions=regions)
        val proposals = mutableListOf<DetectedQuestion>()
        val debug = mutableListOf<AnchorDebug>()
        val columns = mutableListOf<ColumnBounds>()
        val questionRegions=regions.filter { it.role==QuestionPageRegions.Role.QUESTIONS }.sortedBy { it.box.left }
        var pageGutter: Gutter? = if(questionRegions.size==2)
            Gutter(questionRegions[0].box.right,questionRegions[1].box.left) else null
        fun overlaps(a: NormalizedRect, b: NormalizedRect) =
            a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
        for (run in runs) {
            val region = excluded.firstOrNull { overlaps(it.box, run.box) } ?: continue
            debug += AnchorDebug(run.text, run.box, null, region.role.name.lowercase(), "rejected", 0.0, region.reason)
        }
        for (region in regions.filter { it.role == QuestionPageRegions.Role.QUESTIONS }) {
            val box = region.box
            val width = box.right-box.left
            val selected = runs.filter { it.box.left >= box.left && it.box.right <= box.right && it.box.top>=box.top && it.box.bottom<=box.bottom &&
                excluded.none { e -> overlaps(e.box,it.box) } }
            if (selected.isEmpty()) continue
            fun local(r: NormalizedRect) = NormalizedRect((r.left-box.left)/width,r.top,(r.right-box.left)/width,r.bottom)
            fun global(r: NormalizedRect) = NormalizedRect(
                (box.left+r.left*width).coerceIn(box.left,box.right),r.top,
                (box.left+r.right*width).coerceIn(box.left,box.right),r.bottom)
            // Only a bounded binary layout grid is copied, never another rendered page bitmap.
            val x0=(box.left*layout.width).toInt()
            val w=((box.right*layout.width).toInt()-x0).coerceAtLeast(1)
            val ink=BooleanArray(w*layout.height) { i ->
                val x=x0+i%w; val y=i/w
                y.toDouble()/layout.height>=box.top && y.toDouble()/layout.height<box.bottom &&
                layout.occupied(x.coerceAtMost(layout.width-1),y) && excluded.none { e ->
                    x.toDouble()/layout.width >= e.box.left && x.toDouble()/layout.width < e.box.right &&
                    y.toDouble()/layout.height >= e.box.top && y.toDouble()/layout.height < e.box.bottom
                }
            }
            val result=analyzeColumns(page,selected.map { TextRun(it.text,local(it.box)) },Layout(w,layout.height,ink),textSource,region.inferColumns,width,region.recoveredStart?.let { local(it) })
            val offset=columns.size
            columns += result.columnBounds.map { ColumnBounds(box.left+it.left*width,box.left+it.right*width) }
            result.gutter?.let { pageGutter=Gutter(box.left+it.left*width,box.left+it.right*width) }
            debug += result.anchors.map { it.copy(box=global(it.box),column=it.column?.plus(offset)) }
            for (proposal in result.proposals) {
                val mapped=global(proposal.crop)
                if(mapped.top>=box.bottom || mapped.bottom<=box.top) continue
                var crop=NormalizedRect(mapped.left,maxOf(mapped.top,box.top),mapped.right,minOf(mapped.bottom,box.bottom))
                // A compact answer section is an obstacle, not a page-wide footer cutoff.
                // End a preceding crop before it; questions below it remain eligible.
                val stop=excluded.filter { it.role==QuestionPageRegions.Role.ANSWER_KEY && overlaps(crop,it.box) &&
                    it.box.top > crop.top }.minOfOrNull { it.box.top }
                if(stop!=null) crop=NormalizedRect(crop.left,crop.top,crop.right,minOf(crop.bottom,stop))
                if(excluded.any { overlaps(crop,it.box) }) continue
                val column=offset+proposal.id.split("-")[1].toInt()
                val finalCrop=columns[column].clamp(crop) ?: continue
                proposals += proposal.copy(id="$page-$column-${proposal.id.substringAfterLast("-")}",crop=finalCrop,
                    reasons=proposal.reasons+if(stop!=null) listOf("Crop ends before an excluded answer-key region; review boundary") else emptyList())
            }
        }
        return Diagnostics(page,columns.size,pageGutter,debug,proposals,columns,regions)
    }

    private fun analyzeColumns(page: Int, runs: List<TextRun>, layout: Layout, textSource: QuestionTextSource, inferColumns: Boolean = true, horizontalScale: Double = 1.0, recoveredStart: NormalizedRect? = null): Diagnostics {
        // Join adjacent glyphs on a baseline, but never bridge a column-sized gap.
        val lines = mutableListOf<TextRun>()
        for (run in runs.sortedWith(compareBy<TextRun> { it.box.left }.thenBy { it.box.top })) {
            val i = lines.indexOfLast { abs(it.box.bottom - run.box.bottom) < maxOf(0.006, (run.box.bottom - run.box.top) * 0.4) &&
                run.box.left >= it.box.right - 0.003/horizontalScale && run.box.left - it.box.right < 0.012/horizontalScale }
            if (i < 0) lines += run else {
                val old = lines[i]
                lines[i] = TextRun(old.text + (if (run.box.left - old.box.right > 0.002/horizontalScale) " " else "") + run.text,
                    NormalizedRect(old.box.left, minOf(old.box.top, run.box.top), run.box.right, maxOf(old.box.bottom, run.box.bottom)))
            }
        }
        // Answer-key regions have already been removed by role classification.
        // Bare numbered diagram rows must never set a global footer cutoff.
        val footerTop = 0.96
        val body = lines.filter { it.box.top >= 0.025 && it.box.bottom < footerTop }
        val rawAnchors = lines.filter { anchor.containsMatchIn(it.text.trimStart()) }
        val anchors = rawAnchors.filter { it in body && it.box.top < 0.95 }

        // Modes, not a gap in every observed x: stray math numbers or footer words
        // must not fill the gap between the two repeated question-number margins.
        fun modes(items: List<TextRun>): List<List<TextRun>> {
            val groups = mutableListOf<MutableList<TextRun>>()
            for (item in items.sortedBy { it.box.left }) {
                val group = groups.lastOrNull()
                if (group == null || item.box.left - group.first().box.left > 0.035)
                    groups += mutableListOf(item)
                else group += item
            }
            return groups
        }
        fun modePair(items: List<TextRun>, minimum: Int): Pair<Double, Double>? {
            val groups = modes(items).filter { it.size >= minimum }
            return groups.flatMap { l -> groups.mapNotNull { r ->
                val lx=l.map { it.box.left }.sorted()[l.size/2]
                val rx=r.map { it.box.left }.sorted()[r.size/2]
                if (rx-lx > 0.18 && lx < 0.4 && rx in 0.35..0.85 &&
                    body.count { it.box.left < lx+0.04 && it.box.right > rx } <= maxOf(1, body.size/5))
                    Triple(lx,rx,l.size*r.size) else null
            } }.maxByOrNull { it.third }?.let { it.first to it.second }
        }
        val columnGap = if(inferColumns) modePair(anchors, 2) ?: modePair(body, 2) ?: modePair(anchors, 1) else null
        val gutterRange = columnGap?.let { (left, right) ->
            // Robust text edges define the search interval; one crossing publisher mark
            // cannot erase it. Narrow vertical divider ink is not a whitespace veto.
            val ends = body.filter { it.box.left < right-0.08 && it.box.right <= right }
                .map { it.box.right }.sorted()
            val end = ends.getOrNull(((ends.size-1)*0.8).toInt().coerceAtLeast(0)) ?: left+0.12
            val from = maxOf(left+0.12, minOf(end, right-0.025))
            val to = right-0.004
            val scores = ((from*layout.width).toInt()..(to*layout.width).toInt()).map { x ->
                var occupied=0
                for (y in (0.08*layout.height).toInt() until (minOf(footerTop,0.92)*layout.height).toInt())
                    if (layout.occupied(x.coerceIn(0,layout.width-1),y)) occupied++
                // A long, thin divider is positive column evidence, not a rejection.
                x to if (occupied > layout.height*0.45) 0 else occupied
            }
            val minimum=scores.minOfOrNull { it.second } ?: 0
            val quiet=scores.filter { it.second <= minimum + layout.height*0.04 }
            val l=quiet.firstOrNull()?.first?.toDouble()?.div(layout.width) ?: from
            val r=quiet.lastOrNull()?.first?.toDouble()?.div(layout.width) ?: to
            Gutter(l, maxOf(l,r))
        }
        val gutter = gutterRange?.center
        val edges = if (gutter == null) listOf(0.0, 1.0) else listOf(0.0, gutter, 1.0)
        // Assignment still uses the existing midpoint. Cropping uses a separate hard
        // boundary: a narrow printed divider, or the gutter edge nearest the right margin.
        // The midpoint of a broad search valley can lie inside left-column content.
        val cropBounds = if (gutterRange == null) listOf(ColumnBounds(0.0,1.0)) else {
            val yFrom=(0.08*layout.height).toInt()
            val yTo=(minOf(footerTop,0.92)*layout.height).toInt()
            val stripes=mutableListOf<IntRange>()
            var stripeStart: Int?=null
            val first=(gutterRange.left*layout.width).toInt()
            val last=(gutterRange.right*layout.width).toInt().coerceAtMost(layout.width-1)
            for (x in first..last+1) {
                val strong=x<=last && (yFrom until yTo).count { layout.occupied(x,it) } > (yTo-yFrom)*0.55
                if (strong && stripeStart==null) stripeStart=x
                if (!strong && stripeStart!=null) { stripes += stripeStart until x; stripeStart=null }
            }
            val divider=stripes.filter { it.count().toDouble()/layout.width <= 0.012 }.lastOrNull()
            val l=divider?.first?.toDouble()?.div(layout.width) ?: gutterRange.right
            val r=divider?.let { (it.last+1).toDouble()/layout.width } ?: gutterRange.right
            listOf(ColumnBounds(0.0,(l-0.002).coerceAtLeast(0.01)),
                ColumnBounds((r+0.002).coerceAtMost(0.99),1.0))
        }
        val numericShape=Regex("^[)\\]|(]*\\s*[0-9]{1,3}\\s*[.)]?$")
        val anchorDiagnostics=lines.filter { it !in body &&
            (anchor.containsMatchIn(it.text.trim()) || numericShape.matches(it.text.trim())) }.map { run ->
            AnchorDebug(run.text,run.box,if(gutter!=null && run.box.left>=gutter) 1 else 0,
                "footer", "rejected",0.0,"footer/header region")
        }.toMutableList()
        val output = mutableListOf<DetectedQuestion>()
        for (column in 0 until edges.lastIndex) {
            val bounds = cropBounds[column]
            val left = bounds.left; val right = bounds.right
            val selection=QuestionAnchorDetector.select(body.filter {
                it.box.left >= edges[column] && it.box.left < edges[column+1] && it.box.right <= bounds.right+0.025
            },bounds,column,layout,textSource.label,
                anchors.filter { it.box.left < edges[column] || it.box.left >= edges[column+1] }.map { it.box.top },horizontalScale)
            val recovered=recoveredStart?.let { TextRun("Unrecognized question number",it) }
            val group=(selection.accepted.filter { recovered==null || it.box.top>recovered.box.bottom+.025 }+listOfNotNull(recovered)).sortedBy { it.box.top }
            if(recovered!=null) anchorDiagnostics+=AnchorDebug(recovered.text,recovered.box,column,null,"visual recovered",.78,
                "number-lane glyph before spanning raster block and later prose")
            anchorDiagnostics += selection.diagnostics
            group.forEachIndexed { i, start ->
                val next = group.getOrNull(i + 1)
                val top = (start.box.top - 0.006).coerceAtLeast(0.0)
                val proposedLimit = (next?.box?.top?.minus(0.006) ?: minOf(0.97, footerTop)).coerceAtLeast(start.box.bottom)
                // Keep the entire band, including non-text ink and open-ended writing space.
                // Use pixel occupancy to avoid cutting through a diagram at a proposed boundary.
                val xFrom = (left * layout.width).toInt()
                val xTo = (right * layout.width).toInt()
                fun rowInk(row: Int) = (xFrom until xTo).count { x -> layout.occupied(x, row) }
                val proposedY = (proposedLimit * layout.height).toInt().coerceIn(0, layout.height - 1)
                // Search locally for a clean horizontal cut, keeping diagrams and answer space.
                // OCR only supplies anchors; actual ink decides whether the boundary can move.
                val lower = maxOf((start.box.bottom * layout.height).toInt(), proposedY - (layout.height * 0.025).toInt())
                val upper = minOf(layout.height - 1, ((next?.box?.top?.minus(0.002) ?: minOf(0.975, footerTop)) * layout.height).toInt())
                val clearRow = (lower..upper).filter { yy ->
                    (maxOf(lower, yy - 1)..minOf(upper, yy + 1)).all { rowInk(it) <= 3 }
                }.minByOrNull { if (it >= proposedY) it - proposedY else proposedY - it + layout.height }
                val whitespaceLimit = clearRow?.let { it.toDouble() / layout.height } ?: proposedLimit
                // Trim substantial trailing whitespace using same-column raster AND text.
                // Exclude page margins and the divider from occupancy measurements.
                val innerLeft=((left+0.015)*layout.width).toInt().coerceAtMost(layout.width-1)
                val innerRight=((right-0.015)*layout.width).toInt().coerceAtLeast(innerLeft)
                val lastInk=((start.box.bottom*layout.height).toInt() until
                    (whitespaceLimit*layout.height).toInt()).lastOrNull { yy ->
                    (innerLeft until innerRight).count { xx -> layout.occupied(xx,yy) } > 3
                }?.toDouble()?.div(layout.height) ?: start.box.bottom
                val lastText=body.filter { it.box.left >= left && it.box.left < right &&
                    it.box.top >= top && it.box.top < whitespaceLimit }.maxOfOrNull { it.box.bottom } ?: start.box.bottom
                val contentBottom=maxOf(lastInk,lastText)
                val limit=if (whitespaceLimit-contentBottom > 0.08)
                    minOf(whitespaceLimit,contentBottom+0.035) else whitespaceLimit
                val y = (limit * layout.height).toInt().coerceIn(0, layout.height - 1)
                val boundaryInk = ((left * layout.width).toInt() until (right * layout.width).toInt()).count { x -> layout.occupied(x, y) }
                val reasons = mutableListOf<String>()
                selection.diagnostics.firstOrNull { it.box==start.box && it.source=="visual recovered" }?.let {
                    reasons += "Recovered question start: ${it.reason}; review before accepting"
                }
                val choices = lines.filter { it.box.left >= left && it.box.left < right &&
                    it.box.top >= top && it.box.top < limit && option.containsMatchIn(it.text) }
                if (choices.any { it.box.bottom > limit }) reasons += "Answer option touches the lower boundary"
                if (gutter != null) reasons += "Check column split and any shared passage/continuation"
                if (limit < proposedLimit - 0.002) reasons += "Boundary moved upward to whitespace; check for clipped content"
                if (next == null) reasons += "Last question in column: check for continuation and footer"
                if (boundaryInk > 3) reasons += "Content touches the lower boundary; continuation may be clipped"
                if (lines.any { it.box.top >= top && it.box.top < limit && it.box.left < right && it.box.right > right + 0.005 })
                    reasons += "Content crosses a column boundary"
                if (gutter != null) {
                    val gx = (gutter * layout.width).toInt().coerceIn(0,layout.width-1)
                    if (((top*layout.height).toInt() until (limit*layout.height).toInt()).count { yy -> layout.occupied(gx,yy) } > 3)
                        reasons += "Diagram or layout ink touches the gutter; check the crop width"
                }
                if (start.box.top - (group.getOrNull(i - 1)?.box?.bottom ?: 0.0) < 0.012)
                    reasons += "Closely spaced numbering; check subparts"
                // FINAL geometry operation, after every raster/text/padding decision.
                val finalCrop=bounds.clamp(NormalizedRect(left, top, right, limit))
                if (finalCrop != null) output += DetectedQuestion("$page-$column-$i", page, finalCrop, reasons)
            }
        }
        return Diagnostics(page, edges.size-1, gutterRange, anchorDiagnostics, output, cropBounds)
    }
}
