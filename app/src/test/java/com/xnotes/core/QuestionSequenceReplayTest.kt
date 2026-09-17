package com.xnotes.core

import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

/** Development split frozen before inspecting validation pages. Never assert proposal counts. */
class QuestionSequenceReplayTest {
    @Test fun development() = replay("sequence-development")
    @Test fun validation() = replay("sequence-validation")
    private fun replay(folder: String) {
        val dir=File("build/$folder")
        val split=folder.removePrefix("sequence-")
        assumeTrue("Private corpus unavailable",dir.isDirectory)
        val labels=JSONObject(javaClass.getResource("/question-sequence/$split-labels.json")!!.readText())
        assumeTrue("Incomplete private corpus",labels.keys().asSequence().all { File(dir,"page-$it.json.gz").exists() })
        val metrics=JSONArray()
        for(key in labels.keys().asSequence().sortedBy { it.toInt() }) {
            val j=JSONObject(GZIPInputStream(File(dir,"page-$key.json.gz").inputStream()).bufferedReader().use { it.readText() })
            val a=j.getJSONArray("runs")
            val runs=(0 until a.length()).map { i -> val r=a.getJSONArray(i)
                QuestionLayoutDetector.TextRun(r.getString(0),NormalizedRect(r.getDouble(1),r.getDouble(2),r.getDouble(3),r.getDouble(4))) }
            val ink=Base64.getDecoder().decode(j.getString("ink"))
            val d=QuestionLayoutDetector.analyze(key.toInt()-1,runs,QuestionLayoutDetector.Layout(j.getInt("width"),j.getInt("height"),BooleanArray(ink.size) { ink[it].toInt()!=0 }))
            fun rect(b:NormalizedRect)=JSONArray(listOf(b.left,b.top,b.right,b.bottom))
            File(dir,"result-$key.json").writeText(JSONObject()
                .put("regions",JSONArray(d.regions.map { JSONObject().put("role",it.role).put("box",rect(it.box)).put("reason",it.reason) }))
                .put("anchors",JSONArray(d.anchors.map { JSONObject().put("text",it.text).put("box",rect(it.box)).put("source",it.source).put("reason",it.reason) }))
                .put("proposals",JSONArray(d.proposals.map { rect(it.crop) })).toString(2))
            val expected=labels.getJSONArray(key)
            val matched=mutableSetOf<Int>();var recalled=0;var complete=0
            for(i in 0 until expected.length()) {
                val e=expected.getJSONArray(i) // printed marker x/y, final required content right/bottom
                val candidates=d.proposals.withIndex().filter { (_,p) ->
                    kotlin.math.abs(p.crop.top-e.getDouble(1))<.025 &&
                    p.crop.left<=e.getDouble(0)+.003 && p.crop.right>e.getDouble(0)+.015 }
                val best=candidates.minByOrNull { kotlin.math.abs(it.value.crop.top-e.getDouble(1)) }
                if(best!=null && matched.add(best.index)) {
                    recalled++
                    val crop=best.value.crop
                    val includesAnother=(0 until expected.length()).filter { it!=i }.any { other ->
                        val a=expected.getJSONArray(other)
                        a.getDouble(0)+.003 in crop.left..crop.right &&
                            a.getDouble(1)+.004 in crop.top..crop.bottom
                    }
                    if(crop.right>=e.getDouble(2) && crop.bottom>=e.getDouble(3) &&
                        crop.top<=e.getDouble(1)+.003 && !includesAnother) complete++
                }
            }
            metrics.put(JSONObject().put("page",key.toInt()).put("realAnchors",expected.length())
                .put("recalled",recalled).put("falseAnchors",d.proposals.size-matched.size).put("completeCrops",complete))
            d.proposals.forEach { p -> val b=d.columnBounds[p.id.split("-")[1].toInt()]
                assertTrue(p.crop.left>=b.left && p.crop.right<=b.right) }
        }
        File(dir,"metrics.json").writeText(metrics.toString(2))
        val recorded=JSONArray(javaClass.getResource("/question-sequence/$split-metrics.json")!!.readText())
        for(i in 0 until recorded.length()) {
            val old=recorded.getJSONObject(i)
            val now=(0 until metrics.length()).map { metrics.getJSONObject(it) }.single { it.getInt("page")==old.getInt("page") }
            val page=old.getInt("page")
            assertTrue("Page $page anchor recall regression",now.getInt("recalled")>=old.getInt("recalled"))
            assertTrue("Page $page false-anchor regression",now.getInt("falseAnchors")<=old.getInt("falseAnchors"))
            assertTrue("Page $page content coverage regression",now.getInt("completeCrops")>=old.getInt("completeCrops"))
        }
    }
}
