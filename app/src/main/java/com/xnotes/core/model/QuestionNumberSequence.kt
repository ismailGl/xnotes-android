package com.xnotes.core.model

import com.xnotes.core.model.QuestionLayoutDetector.TextRun

/** Page-local evidence only: a new page/test may start at any number. */
internal object QuestionNumberSequence {
    private val marker=Regex("^(?:[Ss][Oo][Rr][Uu]\\s+)?[)\\]|(]*\\s*([0-9]{1,3})(?:[.)]|\\s|$)")
    fun number(run: TextRun): Int? = marker.find(run.text.trim())?.groupValues?.get(1)?.toIntOrNull()
    fun explicit(run: TextRun) = Regex("^(?:[0-9]{1,3}\\s*[.)]|[Ss][Oo][Rr][Uu]\\s+[0-9])").containsMatchIn(run.text.trim())

    /** Compact table rows do not establish a reading lane. Gaps are allowed, not rewarded. */
    fun laneScore(runs: List<TextRun>): Double {
        val ordered=runs.sortedBy { it.box.top }
        var score=ordered.count { explicit(it) }*3.0
        for((a,b) in ordered.zipWithNext()) {
            val delta=(number(b) ?: continue)-(number(a) ?: continue)
            score += when {
                b.box.top-a.box.top<.05 -> -3.0
                delta==1 -> 5.0
                delta in 2..4 -> 1.0
                delta<=0 -> -4.0
                else -> -2.0
            }
        }
        return score
    }

    /** Consecutive neighbours support a shifted marker, never an arbitrary interior number. */
    fun neighbours(candidate: TextRun, candidates: List<TextRun>, xTolerance: Double): Boolean {
        val n=number(candidate) ?: return false
        return candidates.any { other ->
            val m=number(other) ?: return@any false
            kotlin.math.abs(other.box.left-candidate.box.left)<=xTolerance &&
                kotlin.math.abs(other.box.top-candidate.box.top)>.05 &&
                ((other.box.top<candidate.box.top && m==n-1) || (other.box.top>candidate.box.top && m==n+1))
        }
    }
    /** Best supported path, with soft numbering costs. Unknown OCR numbers carry no
     * transition penalty; explicit resets after a separated block remain possible. */
    fun choose(candidates: List<TextRun>, separated: Set<TextRun> = emptySet()): List<TextRun> {
        if(candidates.size<2) return candidates
        val ordered=candidates.sortedBy { it.box.top }
        val scores=DoubleArray(ordered.size);val previous=IntArray(ordered.size) { -1 }
        for(i in ordered.indices) {
            val b=ordered[i];val bn=number(b)
            val own=if(explicit(b)) 8.0 else 5.0
            scores[i]=own
            for(j in 0 until i) {
                val a=ordered[j];val an=number(a)
                val transition=when {
                    an==null || bn==null -> 0.0
                    bn==an+1 -> 3.0
                    bn>an -> 0.0
                    (bn==1 || b in separated || b.text.startsWith("Soru",ignoreCase=true)) && explicit(b) && b.box.top-a.box.bottom>.06 -> -1.0
                    else -> -12.0
                }
                val score=scores[j]+own+transition
                if(score>scores[i]) { scores[i]=score;previous[i]=j }
            }
        }
        var i=scores.indices.maxByOrNull { scores[it] } ?: return emptyList()
        val selected=mutableListOf<TextRun>()
        while(i>=0) { selected+=ordered[i];i=previous[i] }
        return selected.reversed()
    }
}
