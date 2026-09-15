package com.xnotes.core.model

import com.xnotes.core.model.QuestionLayoutDetector.TextRun
import kotlin.math.abs
import java.util.Locale

/** Page furniture is classified before question margins are learned. No publisher identity or colour. */
object QuestionPageRegions {
    enum class Role { QUESTIONS, INSTRUCTIONAL, DOCUMENT, ANSWER_KEY }
    data class Region(val box: NormalizedRect, val role: Role, val confidence: Double, val reason: String)
    private val whole = NormalizedRect(0.0,0.0,1.0,1.0)
    private val entry = Regex("(?<![\\p{L}\\d])[0-9]{1,3}\\s*[.):–-]?\\s*[A-E](?![\\p{L}\\d])")
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
                if(previous!=null && run.box.left-previous.box.right in -0.003..0.012) {
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
        if(contentsRows>=4) return keys+Region(whole,Role.DOCUMENT,0.95,"repeated contents leaders and page references")

        val result=mutableListOf<Region>()
        // Consider off-centre separators as well as the page centre. A sidebar is a tall
        // outer panel with instructional headings; do not exclude a note inside a question.
        data class Panel(val box: NormalizedRect,val score: Int)
        val panels=mutableListOf<Panel>()
        for(step in 20..80) {
            val split=step/100.0
            val active=body.filter { it.box.top in 0.06..0.94 }
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
                    panels += Panel(panel,headings.size*100+inside.size-crossing*20)
            }
        }
        val sidebar=panels.maxWithOrNull(compareBy<Panel> { it.score }.thenBy { -(it.box.right-it.box.left) })
        if(sidebar!=null) {
            result += Region(sidebar.box,Role.INSTRUCTIONAL,0.85,"tall outer panel, instructional headings and separate numbered content")
            val main=if(sidebar.box.left==0.0) NormalizedRect(sidebar.box.right,0.0,1.0,1.0)
                else NormalizedRect(0.0,0.0,sidebar.box.left,1.0)
            result += Region(main,Role.QUESTIONS,0.80,"main content outside instructional panel; infer question columns locally")
        } else result += Region(whole,Role.QUESTIONS,0.50,"unclassified content retained for existing question validation")
        return result+keys
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
        data class Strip(val runs: List<TextRun>,val numbers: List<Int>)
        val strips=mutableListOf<Strip>()
        for(row in rows(runs)) {
            // Split at prose, preserving nearby numbered diagrams/tables as separate content.
            val groups=mutableListOf<MutableList<TextRun>>()
            var group=mutableListOf<TextRun>()
            for(run in row.sortedBy { it.box.left }) {
                val text=run.text.trim()
                val residue=entry.replace(text,"").replace(Regex("[\\s|,;:.-]"),"")
                val fragment=Regex("^(?:[0-9]{1,3}[.)]?|[A-E]|[.)])$").matches(text)
                if(residue.isEmpty() || fragment) {
                    if(group.isNotEmpty() && run.box.left-group.last().box.right>0.20) { groups+=group;group=mutableListOf() }
                    group+=run
                } else if(group.isNotEmpty()) { groups+=group;group=mutableListOf() }
            }
            if(group.isNotEmpty()) groups+=group
            for(items in groups) {
                val text=items.joinToString(" ") { it.text }
                val matches=entry.findAll(text).toList()
                val residue=entry.replace(text,"").replace(Regex("[\\s|,;:.-]"),"")
                if(matches.isNotEmpty() && residue.isEmpty()) {
                    val numbers=matches.map { Regex("[0-9]+").find(it.value)!!.value.toInt() }
                    strips+=Strip(items,numbers)
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
            if(numbers.size<3 || sequential<numbers.size-2) continue
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
