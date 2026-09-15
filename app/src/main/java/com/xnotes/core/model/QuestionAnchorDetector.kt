package com.xnotes.core.model

import com.xnotes.core.model.QuestionLayoutDetector.AnchorDebug
import com.xnotes.core.model.QuestionLayoutDetector.ColumnBounds
import com.xnotes.core.model.QuestionLayoutDetector.Layout
import com.xnotes.core.model.QuestionLayoutDetector.TextRun
import kotlin.math.abs

/** Candidate validation/recovery only. Column inference and final crop ownership stay upstream. */
internal object QuestionAnchorDetector {
    private val strict = Regex("^(?:[0-9]{1,3}\\s*[.)]|[Ss][Oo][Rr][Uu]\\s+[0-9]{1,3}(?:[.):]|\\s|$))")
    private val damaged = Regex("^[)\\]|(]*\\s*[0-9]{1,3}\\s*[.)]?$")
    private val excludedStart = Regex("^(?:[A-E]\\s*[).]|[IVX|]{1,5}\\s*[.)]|[•●])")
    data class Result(val accepted: List<TextRun>, val diagnostics: List<AnchorDebug>)

    fun select(lines: List<TextRun>, bounds: ColumnBounds, column: Int, layout: Layout,
               source: String, peerStarts: List<Double>): Result {
        val numeric = lines.filter { strict.containsMatchIn(it.text.trim()) || damaged.matches(it.text.trim()) }
        fun narrative(run: TextRun) = run.text.count { it.isLetter() } >= 4 &&
            run.box.right-run.box.left >= 0.065 && !excludedStart.containsMatchIn(run.text.trim())
        fun following(run: TextRun) = lines.filter { it !== run &&
            it.box.top >= run.box.top-0.007 && it.box.top <= run.box.bottom+0.065 &&
            it.box.left >= run.box.left-0.008 && narrative(it) }
        // Prefer markers immediately before a stem. This separates the .534 question
        // margin from .565 equations even when the old .035-wide modes merged them.
        val supported = numeric.filter { n -> following(n).any { t ->
            abs(t.box.top-n.box.top)<0.012 && t.box.left-n.box.right in -0.004..0.065
        } || (strict.containsMatchIn(n.text.trim()) && narrative(n)) }
        val early = supported.filter { it.box.left < bounds.left+(bounds.right-bounds.left)*0.30 }
        val stemLines = lines.filter { narrative(it) && it.box.left < bounds.left+(bounds.right-bounds.left)*0.35 }
        val margin = (early.minOfOrNull { it.box.left }
            ?: stemLines.minOfOrNull { it.box.left }?.minus(0.03)
            ?: bounds.left+0.025).coerceAtLeast(bounds.left)
        val stemMargin = stemLines.filter { it.box.left >= margin+0.012 }.groupBy { (it.box.left/0.015).toInt() }
            .maxByOrNull { it.value.size }?.value?.map { it.box.left }?.average() ?: margin+0.03
        val tolerance = 0.020
        val x0=((margin-0.008).coerceAtLeast(bounds.left)*layout.width).toInt()
        val x1=((bounds.right-0.008)*layout.width).toInt().coerceAtLeast(x0+1).coerceAtMost(layout.width)
        val occupied = BooleanArray(layout.height) { y ->
            (x0 until x1).count { x -> layout.occupied(x,y) } >= maxOf(3,(x1-x0)/150)
        }
        fun blankBefore(y: Double): Double {
            val end=(y*layout.height).toInt().coerceIn(0,layout.height-1)
            var previous=end-(0.004*layout.height).toInt()-1
            while(previous>=0 && !occupied[previous]) previous--
            val rasterGap=(end-previous).toDouble()/layout.height
            val textBottom=lines.filter { it.box.bottom < y-0.004 }.maxOfOrNull { it.box.bottom } ?: 0.0
            return minOf(rasterGap,y-textBottom)
        }
        val accepted = mutableListOf<TextRun>()
        val debug = mutableListOf<AnchorDebug>()
        for (n in numeric.sortedBy { it.box.top }) {
            val near=abs(n.box.left-margin)<=tolerance
            val follow=following(n)
            val content = narrative(n) || follow.any { it.text.count { ch -> ch.isLetter() }>=4 }
            val predecessor=lines.any { it !== n && it.box.bottom > n.box.top-0.012 &&
                it.box.top < n.box.top-0.004 && narrative(it) }
            val sameRowStem=follow.any { abs(it.box.top-n.box.top)<0.012 }
            val reason=when {
                !near -> "interior number: not at question margin"
                !content -> "insufficient following content"
                predecessor && !sameRowStem -> "interior number: not a content-block start"
                accepted.any { abs(it.box.top-n.box.top)<0.015 } -> "interior number: duplicate row"
                else -> null
            }
            val isStrict=strict.containsMatchIn(n.text.trim())
            if (reason==null) accepted += n
            debug += AnchorDebug(n.text,n.box,column,reason,
                if(reason!=null) "rejected" else if(isStrict) source else "visual recovered",
                if(reason!=null) 0.15 else if(isStrict) 0.92 else 0.78,
                reason ?: if(isStrict) "question margin and following content" else "damaged/bare marker at question margin with following content")
        }
        // Inspect all separated stems, including ABOVE the first recognized anchor and
        // inside oversized intervals. Never place an anchor just because a column exists.
        val recoverySeeds=lines.filter { (narrative(it) ||
            (it.text.any { ch -> ch.isLetterOrDigit() } && it.box.right-it.box.left>=0.065)) &&
            it.box.left < bounds.left+(bounds.right-bounds.left)*0.35 }
        for (stem in recoverySeeds.sortedBy { it.box.top }) {
            if (stem.box.top < 0.07 || stem.box.top > 0.94 || excludedStart.containsMatchIn(stem.text.trim())) continue
            if (abs(stem.box.left-stemMargin)>0.025 && abs(stem.box.left-margin)>0.02) continue
            if (accepted.any { abs(it.box.top-stem.box.top)<0.025 }) continue
            val gap=blankBefore(stem.box.top)
            val beforeFirst=accepted.none { it.box.top < stem.box.top }
            val previous=accepted.filter { it.box.top<stem.box.top }.maxByOrNull { it.box.top }
            val cluster=lines.filter { it.box.top >= stem.box.top-0.003 && it.box.top < stem.box.top+0.06 && narrative(it) }
            val letters=cluster.sumOf { it.text.count { ch -> ch.isLetter() } }
            val glyph=smallMarker(layout,margin,stem.box)
            val firstEvidence=beforeFirst && gap>=0.018 && letters>=25 &&
                (peerStarts.any { abs(it-stem.box.top)<0.018 } || cluster.size>=2)
            val largeGap=previous!=null && stem.box.top-previous.box.top>0.16 && gap>0.07 && letters>=65 && cluster.size>=2
            if (gap<0.012 || letters<15 || (!glyph && !firstEvidence && !largeGap)) continue
            val marker=TextRun(stem.text,NormalizedRect(margin,minOf(stem.box.top,stem.box.bottom-0.002),
                maxOf(margin+0.003,stem.box.left),stem.box.bottom))
            accepted += marker
            debug += AnchorDebug(stem.text,marker.box,column,null,"visual recovered",
                if(glyph) 0.76 else 0.60,
                if(glyph) "small raster marker before separated text block" else if(firstEvidence)
                    "populated first region before any recognized anchor" else "separated substantial cluster inside oversized anchor gap")
        }
        return Result(accepted.sortedBy { it.box.top },debug)
    }

    /** Small connected component in the number lane, not a divider or body-text union. */
    private fun smallMarker(layout: Layout, margin: Double, stem: NormalizedRect): Boolean {
        val l=((margin-0.009).coerceAtLeast(0.0)*layout.width).toInt()
        val r=(minOf(margin+0.024,stem.left-0.003)*layout.width).toInt().coerceAtMost(layout.width)
        val t=((stem.top-0.006).coerceAtLeast(0.0)*layout.height).toInt()
        val b=((stem.bottom+0.004).coerceAtMost(1.0)*layout.height).toInt()
        if(r<=l || b<=t) return false
        val w=r-l; val h=b-t; val visited=BooleanArray(w*h)
        for(seed in visited.indices) {
            if(visited[seed] || !layout.occupied(l+seed%w,t+seed/w)) continue
            val queue=ArrayDeque<Int>(); queue.add(seed); visited[seed]=true
            var minX=w; var maxX=0; var minY=h; var maxY=0; var count=0
            while(queue.isNotEmpty()) {
                val p=queue.removeFirst(); val x=p%w; val y=p/w; count++
                minX=minOf(minX,x);maxX=maxOf(maxX,x);minY=minOf(minY,y);maxY=maxOf(maxY,y)
                for((dx,dy) in listOf(-1 to 0,1 to 0,0 to -1,0 to 1)) {
                    val nx=x+dx;val ny=y+dy
                    if(nx in 0 until w && ny in 0 until h) {
                        val next=ny*w+nx
                        if(!visited[next] && layout.occupied(l+nx,t+ny)) { visited[next]=true;queue.add(next) }
                    }
                }
            }
            if(count>=3 && (maxY-minY+1).toDouble()/layout.height in 0.003..0.025 &&
                (maxX-minX+1).toDouble()/layout.width in 0.001..0.022 && minY>0 && maxY<h-1) return true
        }
        return false
    }
}
