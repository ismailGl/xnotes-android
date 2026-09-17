package com.xnotes.core.model

import com.xnotes.core.model.QuestionLayoutDetector.TextRun
import kotlin.math.abs
import java.util.Locale

/** Page furniture is classified before question margins are learned. No publisher identity or colour. */
object QuestionPageRegions {
    enum class Role { QUESTIONS, INSTRUCTIONAL, DOCUMENT, ANSWER_KEY }
    data class Region(val box: NormalizedRect, val role: Role, val confidence: Double, val reason: String, val inferColumns: Boolean = true, val recoveredStart: NormalizedRect? = null)
    private val whole = NormalizedRect(0.0,0.0,1.0,1.0)
    private val entry = Regex("(?<![\\p{L}\\d])[0-9]{1,3}\\s*[.):–-]?\\s*[A-E](?![\\p{L}\\d])",RegexOption.IGNORE_CASE)
    private fun folded(s: String) = s.lowercase(Locale.ROOT).replace('ı','i').replace('ö','o')
        .replace('ğ','g').replace('ü','u').replace('ş','s').replace('ç','c').replace("i\u0307","i")
    private val teaching = Regex("^(?:konuyu ogrenelim|ornek|cozum|bilgi|uyari)(?:\\s|:|$)")
    private fun union(runs: List<TextRun>) = NormalizedRect(runs.minOf { it.box.left },runs.minOf { it.box.top },
        runs.maxOf { it.box.right },runs.maxOf { it.box.bottom })

    fun classify(runs: List<TextRun>): List<Region> {
        val keys = answerKeys(runs)
        val clean = runs.filter { r -> keys.none { contains(it.box,r.box) } }
        val body = rows(clean).flatMap { row ->
            val joined=mutableListOf<TextRun>()
            for(run in row.sortedBy { it.box.left }) {
                val previous=joined.lastOrNull()
                if(previous!=null && run.box.left-previous.box.right in -0.01..0.012) {
                    joined[joined.lastIndex]=TextRun(previous.text+" "+run.text,union(listOf(previous,run)))
                } else joined+=run
            }
            joined
        }
        // A contents page has repeated leaders/page references, not merely numbered headings.
        val contentsRows = rows(body).count { row ->
            val text=row.sortedBy { it.box.left }.joinToString(" ") { it.text }
            Regex("[.·…]{3,}").containsMatchIn(text) && Regex("[0-9]+\\s*$").containsMatchIn(text)
        }
        val references=rows(body).mapNotNull { row ->
            val ordered=row.sortedBy { it.box.left }
            val last=ordered.lastOrNull() ?: return@mapNotNull null
            val number=last.text.trim().trimStart('.').toIntOrNull() ?: return@mapNotNull null
            val before=ordered.dropLast(1).lastOrNull() ?: return@mapNotNull null
            if(last.box.left-before.box.right>0.15 && before.text.count(Char::isLetter)>=4) last to number else null
        }
        val increasing=references.zipWithNext().count { (a,b) -> b.second>=a.second }
        val navigation=references.size>=6 && increasing>=references.size*0.8 && contentsRows>=1 &&
            references.last().first.box.top-references.first().first.box.top>0.3
        if(contentsRows>=4 || navigation) return keys+Region(whole,Role.DOCUMENT,0.95,"repeated contents leaders and page references")

        val result=mutableListOf<Region>()
        // Consider off-centre separators as well as the page centre. A sidebar is a tall
        // outer panel with instructional headings; do not exclude a note inside a question.
        data class Panel(val box: NormalizedRect,val score: Int)
        val panels=mutableListOf<Panel>()
        for(step in 20..80) {
            val split=step/100.0
            val active=body.filter { it.box.top in 0.025..0.94 }
            val crossing=active.count { it.box.left<split-0.004 && it.box.right>split+0.004 }
            if(crossing>maxOf(1,active.size/30)) continue
            for(leftSide in listOf(true,false)) {
                val panel=if(leftSide) NormalizedRect(0.0,0.0,split,1.0) else NormalizedRect(split,0.0,1.0,1.0)
                if(panel.right-panel.left>0.42) continue
                val inside=active.filter { contains(panel,it.box) }
                val outside=active.filter { !contains(panel,it.box) }
                val headings=inside.filter { teaching.containsMatchIn(folded(it.text.trim())) }
                val title=headings.any { folded(it.text).startsWith("konuyu ogrenelim") }
                val tall=inside.isNotEmpty() && inside.maxOf { it.box.bottom }-inside.minOf { it.box.top }>0.50
                val substantive=inside.count { it.text.count(Char::isLetter)>12 }>=3
                val questionOutside=outside.any { Regex("^(?:[0-9]{1,3}\\s*[.)]|Soru\\s+[0-9])",RegexOption.IGNORE_CASE).containsMatchIn(it.text.trim()) }
                if(tall && substantive && questionOutside && (title || headings.size>=2))
                    panels += Panel(panel,headings.size*100+inside.count { it.text.count(Char::isLetter)>12 }-crossing*20)
            }
        }
        val sidebar=panels.maxWithOrNull(compareBy<Panel> { it.score }.thenBy { -(it.box.right-it.box.left) })
        if(sidebar!=null) {
            result += Region(sidebar.box,Role.INSTRUCTIONAL,0.85,"tall outer panel, instructional headings and separate numbered content")
            val main=if(sidebar.box.left==0.0) NormalizedRect(sidebar.box.right,0.0,1.0,1.0)
                else NormalizedRect(0.0,0.0,sidebar.box.left,1.0)
            result += questionAreas(main,clean)
        } else result += Region(whole,Role.QUESTIONS,0.50,"unclassified content retained for existing question validation")
        return result+keys
    }

    /** A sidebar changes the page hierarchy. Within its main area, repeated marker lanes
     * define question columns; ordinary text/table-cell lanes are not independent columns. */
    private fun questionAreas(main: NormalizedRect, runs: List<TextRun>): List<Region> {
        val marker=Regex("^(?:[0-9]{1,3}\\s*[.)](?:\\s|$)|[0-9]{1,3}$|Soru\\s+[0-9])",RegexOption.IGNORE_CASE)
        val groups=mutableListOf<MutableList<TextRun>>()
        for(run in runs.filter { contains(main,it.box) && it.box.top in 0.04..0.9 && marker.containsMatchIn(it.text.trim()) &&
                (Regex("^[0-9]{1,3}\\s*[.)]").containsMatchIn(it.text.trim()) || runs.none { previous -> previous !== it && abs(previous.box.top-it.box.top)<0.008 &&
                    previous.box.right<=it.box.left && it.box.left-previous.box.right<0.014 &&
                    previous.text.any(Char::isLetterOrDigit) }) }
            .sortedBy { it.box.left }) {
            val group=groups.lastOrNull()
            if(group==null || run.box.left-group.first().box.left>0.025) groups+=mutableListOf(run) else group+=run
        }
        val lanes=groups.filter { g ->
            val x=g.first().box.left
            val stems=runs.filter { it.box.left in (x+0.015)..(x+0.04) && it.text.count(Char::isLetter)>=4 }
            (g.map { (it.box.top/0.05).toInt() }.distinct().size>=2 ||
                stems.map { (it.box.top/0.03).toInt() }.distinct().size>=4) && g.minOf { it.box.top }<0.65
        }
        val width=main.right-main.left
        val left=lanes.firstOrNull { it.first().box.left<main.left+width*0.25 }
        fun score(lane: List<TextRun>): Double {
            val x=lane.first().box.left
            val stemRows=runs.filter { it.box.left in (x+0.015)..(x+0.04) && it.text.count(Char::isLetter)>=4 }
                .map { (it.box.top/0.03).toInt() }.distinct().size
            return QuestionNumberSequence.laneScore(lane)+minOf(2.0,stemRows/4.0)
        }
        val right=if(left==null) null else lanes.filter { it.first().box.left-left.first().box.left>width*0.25 }
            .filter { score(it)>0.0 }
            .maxByOrNull { score(it) }
        if(right==null) return listOf(Region(main,Role.QUESTIONS,0.75,
            "main question area: no second repeated question-marker lane",false))
        val margin=right.map { it.box.left }.sorted()[right.size/2]
        // Keep every right-column marker inside its column. The split is in the small
        // gap before that lane, not the often much wider gap before the prose lane.
        val split=margin-0.012
        return listOf(
            Region(NormalizedRect(main.left,0.0,split-0.002,1.0),Role.QUESTIONS,0.85,"left question-marker lane inside main area",false),
            Region(NormalizedRect(split+0.002,0.0,main.right,1.0),Role.QUESTIONS,0.85,"right question-marker lane inside main area",false))
    }

    private fun contains(a: NormalizedRect,b: NormalizedRect) = b.left>=a.left && b.right<=a.right && b.top>=a.top && b.bottom<=a.bottom
    private fun rows(runs: List<TextRun>): List<List<TextRun>> {
        val result=mutableListOf<MutableList<TextRun>>()
        for(run in runs.sortedBy { it.box.top }) {
            val row=result.lastOrNull()
            if(row==null || abs(row.first().box.top-run.box.top)>0.012) result+=mutableListOf(run) else row+=run
        }
        return result
    }
    private fun answerKeys(runs: List<TextRun>): List<Region> {
        data class Strip(val runs: List<TextRun>,val numbers: List<Int>,val orphanChoices: Int)
        val strips=mutableListOf<Strip>()
        for(row in rows(runs)) {
            // Split at prose, preserving nearby numbered diagrams/tables as separate content.
            val groups=mutableListOf<MutableList<TextRun>>()
            var group=mutableListOf<TextRun>()
            for(run in row.sortedBy { it.box.left }) {
                val text=run.text.trim()
                val residue=entry.replace(text,"").replace(Regex("[\\s|,;:.-]"),"")
                val fragment=Regex("^(?:[0-9]{1,3}[.)]?|[A-E]|[.)])$",RegexOption.IGNORE_CASE).matches(text)
                val partial=entry.replace(text," ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val completeCount=entry.findAll(text).count()
                val compactPartial=completeCount>=2 && partial.size<=completeCount && partial.all {
                    Regex("(?:[A-E]|[0-9]{1,3}[.)]?)",RegexOption.IGNORE_CASE).matches(it)
                }
                if(residue.isEmpty() || fragment || compactPartial) {
                    if(group.isNotEmpty() && run.box.left-group.last().box.right>0.20) { groups+=group;group=mutableListOf() }
                    group+=run
                } else if(group.isNotEmpty()) { groups+=group;group=mutableListOf() }
            }
            if(group.isNotEmpty()) groups+=group
            for(items in groups) {
                val text=items.joinToString(" ") { it.text }
                val matches=entry.findAll(text).toList()
                val residue=entry.replace(text,"").replace(Regex("[\\s|,;:.-]"),"")
                val leftovers=entry.replace(text," ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val fragmentsOnly=leftovers.all { Regex("(?:[A-E]|[0-9]{1,3}[.)]?)",RegexOption.IGNORE_CASE).matches(it) }
                if(matches.isNotEmpty() && (residue.isEmpty() || (fragmentsOnly && leftovers.size<=matches.size))) {
                    val numbers=matches.map { Regex("[0-9]+").find(it.value)!!.value.toInt() }
                    strips+=Strip(items,numbers,leftovers.count { Regex("[A-E]",RegexOption.IGNORE_CASE).matches(it) })
                }
            }
        }
        // Connected compact rows form grids; numbering may run down columns or across rows.
        val pending=strips.toMutableList()
        val output=mutableListOf<Region>()
        while(pending.isNotEmpty()) {
            val component=mutableListOf(pending.removeAt(0))
            var changed=true
            while(changed) {
                changed=false
                val iterator=pending.iterator()
                while(iterator.hasNext()) {
                    val next=iterator.next();val b=union(next.runs)
                    if(component.any { s -> val a=union(s.runs)
                        abs(a.top-b.top)<0.055 && a.left<b.right+0.04 && b.left<a.right+0.04 }) {
                        component+=next;iterator.remove();changed=true
                    }
                }
            }
            val numbers=component.flatMap { it.numbers }.sorted()
            val sequential=numbers.zipWithNext().count { (a,b) -> b==a+1 }
            val orphanChoices=component.sumOf { it.orphanChoices }
            val enough=numbers.size>=3 || (numbers.size==2 && orphanChoices>=2)
            if(!enough || numbers.distinct().size!=numbers.size || sequential<maxOf(1,(numbers.size-1)/2)) continue
            val items=component.flatMap { it.runs }
            val box=union(items)
            // Three isolated short questions spread down a page do not form a compact key.
            if((box.bottom-box.top)/numbers.size>0.04) continue
            // Labels embedded in a diagram/table have intervening or immediately following
            // prose. Do not mask that content merely because some cells resemble choices.
            val nearbyProse=runs.any { r -> r !in items && r.text.count(Char::isLetter)>10 &&
                r.box.left<box.right && r.box.right>box.left &&
                r.box.top>=box.top && r.box.top<box.bottom+0.035 &&
                !Regex("^[0-9]{1,3}\\s*[.)]").containsMatchIn(r.text.trim()) }
            if(nearbyProse) continue
            val padded=NormalizedRect((box.left-0.003).coerceAtLeast(0.0),(box.top-0.008).coerceAtLeast(0.0),
                (box.right+0.003).coerceAtMost(1.0),(box.bottom+0.008).coerceAtMost(1.0))
            output+=Region(padded,Role.ANSWER_KEY,0.95,
                "answer-key region: compact sequential number/choice entries without question prose"+
                    if(box.top>0.84) " (footer position)" else "")
        }
        return output
    }
}
