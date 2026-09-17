package com.xnotes.core.model

import com.xnotes.core.model.QuestionLayoutDetector.TextRun
import com.xnotes.core.model.QuestionLayoutDetector.Layout
import com.xnotes.core.model.QuestionPageRegions.Region
import com.xnotes.core.model.QuestionPageRegions.Role
import kotlin.math.abs

/** Refines question geometry, never the role of a sidebar, document, or answer key. */
internal object QuestionReadingBands {
    fun refine(regions: List<Region>, runs: List<TextRun>, layout: Layout): List<Region> {
        val areas=regions.filter { it.role==Role.QUESTIONS }.sortedBy { it.box.left }
        if(areas.size!=2 || areas.any { it.inferColumns }) return regions
        val left=areas[0]; val right=areas[1]
        val split=(left.box.right+right.box.left)/2
        val clean=runs.filter { r -> r.box.left>=left.box.left && r.box.right<=right.box.right &&
            regions.none { it.role!=Role.QUESTIONS && r.box.left<it.box.right && r.box.right>it.box.left &&
                r.box.top<it.box.bottom && r.box.bottom>it.box.top } }
        val numbers=clean.filter { QuestionNumberSequence.explicit(it) }
        val rightNumbers=numbers.filter { it.box.left in right.box.left..(right.box.left+.06) }
        val lastRight=rightNumbers.maxOfOrNull { it.box.bottom } ?: return regions
        val margin=numbers.filter { it.box.left<left.box.left+(left.box.right-left.box.left)*.25 }
            .minOfOrNull { it.box.left } ?: return regions
        // Join word boxes only across a normal inter-word gap. Two independent columns
        // with a common baseline are not evidence for a spanning question.
        val joined=mutableListOf<TextRun>()
        for(r in clean.sortedBy { it.box.left }) {
            val i=joined.indexOfLast { abs(it.box.top-r.box.top)<.007 && r.box.left-it.box.right in -.002.. .012 }
            if(i<0) joined+=r else { val a=joined[i];joined[i]=TextRun(a.text+" "+r.text,
                NormalizedRect(a.box.left,minOf(a.box.top,r.box.top),r.box.right,maxOf(a.box.bottom,r.box.bottom))) }
        }
        val crossing=joined.filter { it.box.left<split-.08 && it.box.right>split+.08 &&
            it.box.top>lastRight+.06 && it.box.top<.92 && it.text.count(Char::isLetter)>25 }
        if(crossing.size<2) return regions
        val first=crossing.minOf { it.box.top }
        val glyphs=markerComponents(layout,margin,maxOf(lastRight+.04,first-.25),first)
        val glyph=glyphs.firstOrNull { g ->
            val x0=((split-.045)*layout.width).toInt();val x1=((split+.045)*layout.width).toInt()
            val denseRows=((g.bottom*layout.height).toInt() until (first*layout.height).toInt()).count { y ->
                (x0 until x1).count { x -> layout.occupied(x,y) }>(x1-x0)/2 }
            denseRows>layout.height*.025
        } ?: return regions
        val boundary=(glyph.top-.012).coerceAtLeast(lastRight)
        val topAreas=areas.map { it.copy(box=NormalizedRect(it.box.left,0.0,it.box.right,boundary),
            reason=it.reason+"; upper reading band") }
        val wide=Region(NormalizedRect(left.box.left,boundary,right.box.right,1.0),Role.QUESTIONS,.78,
            "local spanning question: crossing prose, raster block and number-lane glyph",false,glyph)
        return regions.filter { it.role!=Role.QUESTIONS }+topAreas+wide
    }

    private fun markerComponents(layout: Layout, margin: Double, top: Double, bottom: Double): List<NormalizedRect> {
        val l=((margin-.006)*layout.width).toInt().coerceAtLeast(0)
        val r=((margin+.021)*layout.width).toInt().coerceAtMost(layout.width)
        val t=(top*layout.height).toInt();val b=(bottom*layout.height).toInt()
        if(r<=l || b<=t) return emptyList()
        val w=r-l;val h=b-t;val seen=BooleanArray(w*h);val result=mutableListOf<NormalizedRect>()
        for(seed in seen.indices) {
            if(seen[seed] || !layout.occupied(l+seed%w,t+seed/w)) continue
            val q=ArrayDeque<Int>();q.add(seed);seen[seed]=true
            var minX=w;var maxX=0;var minY=h;var maxY=0;var count=0
            while(q.isNotEmpty()) {
                val i=q.removeFirst();val x=i%w;val y=i/w;count++
                minX=minOf(minX,x);maxX=maxOf(maxX,x);minY=minOf(minY,y);maxY=maxOf(maxY,y)
                for((dx,dy) in listOf(-1 to 0,1 to 0,0 to -1,0 to 1)) {
                    val xx=x+dx;val yy=y+dy
                    if(xx in 0 until w && yy in 0 until h && !seen[yy*w+xx] && layout.occupied(l+xx,t+yy)) {
                        seen[yy*w+xx]=true;q.add(yy*w+xx)
                    }
                }
            }
            if(count>=3 && maxX-minX>=1 && (maxY-minY+1).toDouble()/layout.height in .004.. .023 && minY>0 && maxY<h-1)
                result+=NormalizedRect((l+minX).toDouble()/layout.width,(t+minY).toDouble()/layout.height,
                    (l+maxX+1).toDouble()/layout.width,(t+maxY+1).toDouble()/layout.height)
        }
        return result.sortedBy { it.top }
    }
}
