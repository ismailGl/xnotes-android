package com.xnotes.core.verification

import com.xnotes.core.model.*
import kotlin.math.ceil
import kotlin.math.floor

enum class SemanticAction { KEEP, DELETE, MERGE, SPLIT, MISSED }
data class SemanticDecision(val action: SemanticAction, val target: String)
data class LocalBoundaryCandidate(val id: String, val action: SemanticAction,
    val proposals: List<String>, val rectangles: List<NormalizedRect>, val reason: String)

/** Immutable geometry evidence, generated before any provider call. Never retains raster pixels. */
data class LocalVerificationPlan(val proposals: List<VerifierProposal>,
    val allowed: List<NormalizedRect>, val excluded: List<NormalizedRect>,
    val candidates: List<LocalBoundaryCandidate>) {
    fun safe(box: NormalizedRect) = allowed.any { contains(it, box) } && excluded.none { overlaps(it, box) }

    /** AI can select existing geometry only. Rejected corrections leave their inputs intact. */
    fun resolve(result: VerificationResult): VerificationResult {
        val decisions = requireNotNull(result.decisions) { "Local rule: semantic decisions required" }
        require(decisions.map { it.target }.distinct().size == decisions.size) { "Local rule: duplicate semantic target" }
        val operations = mutableListOf<VerificationOperation>()
        val audit = mutableListOf<String>()
        val targets = proposals.mapIndexed { i, p -> "P${i+1}" to p }.toMap()
        val output = mutableListOf<NormalizedRect>()
        // Reject all conflicting decisions, independent of response ordering.
        fun inputs(d: SemanticDecision) = if (d.action in listOf(SemanticAction.KEEP, SemanticAction.DELETE))
            listOfNotNull(targets[d.target]?.id) else candidates.find { it.id == d.target }?.proposals.orEmpty()
        val conflicts = decisions.flatMap(::inputs).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        for (d in decisions) {
            val p = targets[d.target]
            val c = candidates.find { it.id == d.target }
            val ids = inputs(d)
            val boxes = if (d.action == SemanticAction.KEEP) listOfNotNull(p?.crop) else c?.rectangles.orEmpty()
            val reject = when {
                ids.any { it in conflicts } -> "conflicting decisions for the same proposal"
                d.action in listOf(SemanticAction.KEEP, SemanticAction.DELETE) && p == null -> "unknown proposal ID"
                d.action !in listOf(SemanticAction.KEEP, SemanticAction.DELETE) && (c == null || c.action != d.action) -> "unknown or incompatible local candidate"
                d.action != SemanticAction.DELETE && boxes.any { !safe(it) } -> "outside classified QUESTION bounds or intersects excluded region"
                d.action !in listOf(SemanticAction.KEEP, SemanticAction.DELETE) && boxes.any { b ->
                    proposals.any { other -> other.id !in ids && overlaps(other.crop, b) &&
                        proposals.filter { it.id in ids }.none { contains(it.crop,b) && overlaps(it.crop,other.crop) } } || output.any { overlaps(it, b) }
                } -> "correction overlaps another proposal or selected candidate"
                else -> null
            }
            audit += "${d.action} ${d.target}\nOriginal: ${proposals.filter { it.id in ids }.map { it.crop }}\nLocal candidate: ${c?.let { it.id + " · " + it.reason } ?: "original proposal"}\n" +
                if (reject != null) "Rejected: $reject; originals unchanged" else "Final: ${if(d.action == SemanticAction.DELETE) emptyList() else boxes}"
            if (reject != null) continue
            when (d.action) {
                SemanticAction.KEEP -> operations += VerificationOperation(VerificationAction.KEEP, p!!.id)
                SemanticAction.DELETE -> operations += VerificationOperation(VerificationAction.DELETE, p!!.id)
                else -> {
                    ids.forEach { operations += VerificationOperation(VerificationAction.DELETE, it) }
                    boxes.forEach { operations += VerificationOperation(VerificationAction.ADD,
                        left=it.left, top=it.top, right=it.right, bottom=it.bottom) }
                }
            }
            output += boxes
        }
        for ((i,p) in proposals.withIndex()) if (decisions.none { p.id in inputs(it) }) {
            audit += "P${i+1}\nOriginal: ${p.crop}\nGemini: no decision\nLocal candidate: none\nFinal: ${p.crop}\nUnchanged; manual review required"
        }
        return VerificationResult(operations, listOfNotNull(result.debugResponse, audit.joinToString("\n\n")).joinToString("\n"))
    }

    companion object {
        fun contains(a: NormalizedRect, b: NormalizedRect) = b.left >= a.left && b.right <= a.right && b.top >= a.top && b.bottom <= a.bottom
        fun overlaps(a: NormalizedRect, b: NormalizedRect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
        private fun intersection(a: NormalizedRect, b: NormalizedRect): NormalizedRect? {
            val l=maxOf(a.left,b.left); val r=minOf(a.right,b.right); val t=maxOf(a.top,b.top); val z=minOf(a.bottom,b.bottom)
            return if(r-l >= .005 && z-t >= .005) NormalizedRect(l,t,r,z) else null
        }
        private fun subtract(a: NormalizedRect, b: NormalizedRect): List<NormalizedRect> {
            val i=intersection(a,b) ?: return listOf(a)
            return listOf(listOf(a.left,a.top,a.right,i.top),listOf(a.left,i.bottom,a.right,a.bottom),
                listOf(a.left,i.top,i.left,i.bottom),listOf(i.right,i.top,a.right,i.bottom))
                .filter { it[2]-it[0]>=.005 && it[3]-it[1]>=.005 }.map { NormalizedRect(it[0],it[1],it[2],it[3]) }
        }

        fun build(proposals: List<VerifierProposal>, runs: List<QuestionLayoutDetector.TextRun>,
                  layout: QuestionLayoutDetector.Layout, diagnostics: QuestionLayoutDetector.Diagnostics): LocalVerificationPlan {
            val regions=diagnostics.regions
            val excluded=regions.filter { it.role != QuestionPageRegions.Role.QUESTIONS }.map { it.box }
            val allowed=regions.filter { it.role == QuestionPageRegions.Role.QUESTIONS }.flatMap { region ->
                if (!region.inferColumns || diagnostics.columnBounds.isEmpty()) listOf(region.box) else
                    diagnostics.columnBounds.mapNotNull { intersection(region.box,NormalizedRect(it.left,region.box.top,it.right,region.box.bottom)) }
            }.distinct()
            val candidates=mutableListOf<LocalBoundaryCandidate>()
            fun optionCount(b: NormalizedRect, letter: Char) = runs.count { overlaps(b,it.box) &&
                Regex("(?:^|\\s)$letter\\s*[).](?:\\s|$)").containsMatchIn(it.text) }
            fun optionSet(b: NormalizedRect) = optionCount(b,'A')>0 && (optionCount(b,'D')>0 || optionCount(b,'E')>0)
            fun markerStart(b: NormalizedRect): Boolean = runs.any { r -> contains(b,r.box) &&
                r.box.left <= b.left+(b.right-b.left)*.25 &&
                Regex("^\\s*[0-9]{1,3}\\s*[.)]?\\s*$").matches(r.text) &&
                runs.none { text -> contains(b,text.box) && text.box.bottom < r.box.top && text.text.count(Char::isLetter)>4 }
            }
            fun safe(b: NormalizedRect) = allowed.any { contains(it,b) } && excluded.none { overlaps(it,b) }
            fun add(action: SemanticAction, ids: List<String>, boxes: List<NormalizedRect>, reason: String) {
                if(boxes.isEmpty() || boxes.any { !safe(it) } || candidates.any { it.action==action && it.proposals==ids && it.rectangles==boxes }) return
                candidates += LocalBoundaryCandidate("C${candidates.size+1}", action, ids, boxes, reason)
            }
            // Full row occupancy includes images/diagrams, not only recognized text.
            fun gaps(b: NormalizedRect): List<Double> {
                val x0=floor(b.left*layout.width).toInt().coerceIn(0,layout.width-1)
                val x1=ceil(b.right*layout.width).toInt().coerceIn(x0+1,layout.width)
                val y0=ceil(b.top*layout.height).toInt(); val y1=floor(b.bottom*layout.height).toInt()
                val result=mutableListOf<Double>(); var start=-1
                for(y in y0 until y1) {
                    val blank=(x0 until x1).none { layout.occupied(it,y) } && runs.none { it.box.top <= y.toDouble()/layout.height && it.box.bottom >= y.toDouble()/layout.height && overlaps(it.box,b) }
                    if(blank && start<0) start=y
                    if(!blank && start>=0) {
                        if((y-start).toDouble()/layout.height >= .009 && start>y0 && y<y1) result += (start+y)/2.0/layout.height
                        start=-1
                    }
                }
                return result
            }
            fun substantial(b: NormalizedRect) = runs.filter { contains(b,it.box) }.sumOf { it.text.count(Char::isLetterOrDigit) } >= 24 &&
                b.right-b.left >= .06 && b.bottom-b.top >= .035
            // Additional alternatives inside existing clamps, never a change to the 4B lanes.
            // Repeated isolated printed numbers can locate a question lane inside a broad proposal.
            fun localLanes(b: NormalizedRect): List<NormalizedRect> {
                val groups=mutableListOf<MutableList<QuestionLayoutDetector.TextRun>>()
                for(r in runs.filter { contains(b,it.box) && Regex("^\\s*[0-9]{1,3}\\s*[.)]?\\s*$").matches(it.text) }.sortedBy { it.box.left }) {
                    val g=groups.lastOrNull()
                    if(g==null || r.box.left-g.first().box.left>.02) groups+=mutableListOf(r) else g+=r
                }
                val margins=groups.filter { g -> g.size>=2 && g.maxOf { it.box.top }-g.minOf { it.box.top }>.1 }
                    .map { g -> maxOf(b.left,g.minOf { it.box.left }-.01) }.filter { it>b.left+.025 && it<b.right-.06 }
                val cuts=(listOf(b.left)+margins+listOf(b.right)).distinct().sorted()
                return cuts.zipWithNext().filter { it.second-it.first>=.06 }.map { (l,r) -> NormalizedRect(l,b.top,r,b.bottom) }
            }
            fun questionTop(b: NormalizedRect): NormalizedRect {
                val first=runs.filter { contains(b,it.box) && it.box.left<=b.left+(b.right-b.left)*.25 &&
                    Regex("^\\s*[0-9]{1,3}\\s*[.)]?\\s*$").matches(it.text) }.minByOrNull { it.box.top }
                return if(first!=null && first.box.top-b.top<.15)
                    NormalizedRect(b.left,maxOf(b.top,first.box.top-.006),b.right,b.bottom) else b
            }
            fun questionSpans(b: NormalizedRect): List<NormalizedRect> {
                val markers=runs.filter { contains(b,it.box) && Regex("^\\s*[0-9]{1,3}\\s*[.)]?\\s*$").matches(it.text) }
                val margin=markers.minOfOrNull { it.box.left } ?: return emptyList()
                if(margin>b.left+(b.right-b.left)*.25) return emptyList()
                val starts=mutableListOf<Double>()
                for(r in markers.filter { it.box.left<=margin+.02 }.sortedBy { it.box.top }) {
                    if(starts.isEmpty() || r.box.top-starts.last()>.04) starts+=maxOf(b.top,r.box.top-.006)
                }
                return (starts+listOf(b.bottom)).zipWithNext().filter { it.second-it.first>=.035 }
                    .map { (t,z) -> NormalizedRect(b.left,t,b.right,z) }
            }
            fun tight(b: NormalizedRect): NormalizedRect? {
                val xs=(ceil(b.left*layout.width).toInt() until floor(b.right*layout.width).toInt())
                val ys=(ceil(b.top*layout.height).toInt() until floor(b.bottom*layout.height).toInt())
                var l=layout.width; var r=-1; var t=layout.height; var z=-1
                for(y in ys) for(x in xs) if(layout.occupied(x,y)) { l=minOf(l,x);r=maxOf(r,x);t=minOf(t,y);z=maxOf(z,y) }
                for(run in runs.filter { contains(b,it.box) }) {
                    l=minOf(l,floor(run.box.left*layout.width).toInt()); r=maxOf(r,ceil(run.box.right*layout.width).toInt())
                    t=minOf(t,floor(run.box.top*layout.height).toInt()); z=maxOf(z,ceil(run.box.bottom*layout.height).toInt())
                }
                if(r<l || z<t) return null
                return intersection(b,NormalizedRect(maxOf(b.left,(l-2.0)/layout.width),maxOf(b.top,(t-2.0)/layout.height),
                    minOf(b.right,(r+3.0)/layout.width),minOf(b.bottom,(z+3.0)/layout.height)))
            }
            for(p in proposals) {
                val slices=allowed.mapNotNull { intersection(p.crop,it) }.filter(::substantial)
                if(slices.size>1 && slices.all(::safe)) add(SemanticAction.SPLIT,listOf(p.id),slices,"classified question regions")
                for(b in slices.flatMap(::localLanes).map(::questionTop).distinct()) {
                    val whitespace=gaps(b)
                    val boundaries=questionSpans(b).drop(1).mapNotNull { span -> whitespace.lastOrNull { it<=span.top } }.distinct()
                    if(boundaries.size>=2) {
                        val parts=(listOf(b.top)+boundaries+listOf(b.bottom)).zipWithNext().map { (t,z) -> NormalizedRect(b.left,t,b.right,z) }
                        if(parts.all { substantial(it) && optionSet(it) && optionCount(it,'A')<=1 })
                            add(SemanticAction.SPLIT,listOf(p.id),parts,"multiple locally anchored question spans separated by whitespace")
                    }
                    for(y in whitespace) {
                        val boxes=listOf(NormalizedRect(b.left,b.top,b.right,y),NormalizedRect(b.left,y,b.right,b.bottom))
                        // A gap between a diagram and its stem/options is not a question boundary.
                        // Require independent question-start or completed option-set evidence on both sides.
                        if(boxes.all(::substantial) && boxes.all { (markerStart(it) || optionSet(it)) && optionCount(it,'A')<=1 } &&
                            (markerStart(boxes[1]) || boxes.all(::optionSet)))
                            add(SemanticAction.SPLIT,listOf(p.id),boxes,"raster/text gap with independent question-start or option-set evidence")
                    }
                }
            }
            for(area in allowed) {
                val inside=proposals.filter { contains(area,it.crop) }.sortedBy { it.crop.top }
                for(start in inside.indices) for(size in 2..minOf(4,inside.size-start)) {
                    val group=inside.subList(start,start+size)
                    if(group.count { optionSet(it.crop) } >= 2) continue
                    val box=NormalizedRect(group.minOf { it.crop.left },group.minOf { it.crop.top },group.maxOf { it.crop.right },group.maxOf { it.crop.bottom })
                    if(proposals.none { it !in group && overlaps(it.crop,box) }) add(SemanticAction.MERGE,group.map { it.id },listOf(box),"union of adjacent existing proposals within QUESTION region")
                }
                // Preserve existing column clamps. Region bands may have different widths/heights.
                val lanes=if(regions.firstOrNull { it.box==area }?.inferColumns==false) listOf(area) else
                    diagnostics.columnBounds.mapNotNull { intersection(area,NormalizedRect(it.left,area.top,it.right,area.bottom)) }.ifEmpty { listOf(area) }
                for(lane in lanes.flatMap(::localLanes).distinct()) {
                    var uncovered=listOf(lane)
                    for(obstacle in excluded+proposals.map { it.crop }) uncovered=uncovered.flatMap { subtract(it,obstacle) }
                    for(b in uncovered.filter(::substantial)) {
                        fun singleQuestionEvidence(part: NormalizedRect) = optionSet(part) && optionCount(part,'A')<=1 && optionCount(part,'E')<=1
                        for(span in questionSpans(b)) tight(span)?.takeIf { substantial(it) && singleQuestionEvidence(it) }?.let {
                            add(SemanticAction.MISSED,emptyList(),listOf(it),"uncovered question-number span including a complete option set and raster content")
                        }
                        tight(questionTop(b))?.takeIf { substantial(it) && singleQuestionEvidence(it) }?.let { add(SemanticAction.MISSED,emptyList(),listOf(it),"uncovered QUESTION content; locally bounded raster extent") }
                        // Do not offer isolated paragraph/option clusters as missing questions.
                        // Number spans keep all intervening image and text groups together; a single
                        // uncovered area can also qualify without a recognized number.
                    }
                }
            }
            return LocalVerificationPlan(proposals,allowed,excluded,candidates)
        }
    }
}
