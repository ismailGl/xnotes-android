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

/** Optional local replay. Private exported pages are never required or committed for CI. */
class QuestionAnchorReplayTest {
    @Test fun reportedPagesRecoverStartsWithoutLosingColumnBounds() {
        val dir=listOf(File("build/anchor-replay"),File("app/build/anchor-replay")).firstOrNull { it.isDirectory }
        assumeTrue("Local replay exports unavailable",dir!=null)
        for (number in listOf(12,14,16,20,22,24,25,28,30)) {
            val input=File(dir,"page-$number.json.gz")
            assumeTrue("Missing replay $number",input.exists())
            val json=JSONObject(GZIPInputStream(input.inputStream()).bufferedReader().use { it.readText() })
            val runs=json.getJSONArray("runs").let { array -> (0 until array.length()).map { i ->
                val a=array.getJSONArray(i)
                QuestionLayoutDetector.TextRun(a.getString(0),NormalizedRect(a.getDouble(1),a.getDouble(2),a.getDouble(3),a.getDouble(4)))
            } }
            val ink=Base64.getDecoder().decode(json.getString("ink"))
            val layout=QuestionLayoutDetector.Layout(json.getInt("width"),json.getInt("height"),BooleanArray(ink.size) { ink[it].toInt()!=0 })
            val d=QuestionLayoutDetector.analyze(number-1,runs,layout)
            val result=JSONObject().put("page",number).put("columns",d.columnCount)
                .put("anchors",JSONArray().apply { d.anchors.forEach { a -> put(JSONObject()
                    .put("text",a.text).put("x",a.box.left).put("y",a.box.top).put("column",a.column)
                    .put("source",a.source).put("reason",a.reason)) } })
                .put("proposals",JSONArray().apply { d.proposals.forEach { p -> put(JSONObject().put("id",p.id).put("crop",p.crop.toString())) } })
            File(dir,"result-$number.json").writeText(result.toString(2))
            val expectedCount=mapOf(12 to 7,14 to 5,16 to 6,20 to 4,22 to 4,24 to 4,25 to 3,28 to 6,30 to 4)
            assertEquals("Page $number count",expectedCount[number],d.proposals.size)
            assertEquals("Page $number columns",2,d.columnCount)
            d.proposals.forEach { p ->
                val bounds=d.columnBounds[p.id.split("-")[1].toInt()]
                assertTrue(p.crop.left>=bounds.left && p.crop.right<=bounds.right)
            }
            val wantedColumn=if(number==12) 0 else 1
            val wantedY=if(number==12) 0.345 else 0.126
            assertTrue("Page $number missing start near $wantedY",d.anchors.any {
                it.excluded==null && it.column==wantedColumn && kotlin.math.abs(it.box.top-wantedY)<0.015
            })
            assertFalse("Page $number footer proposal",d.proposals.any { it.crop.top>0.90 })
            if(number==14 || number==30) assertTrue("Interior number rejected on $number",d.anchors.any {
                it.source=="rejected" && it.box.left>0.55 && it.box.top<0.4 && it.reason.contains("interior number")
            })
            // Keep the actual raster but remove the already-damaged OCR marker entirely.
            // This verifies recovery is not merely a more permissive number regex.
            val noMarker=runs.filterNot { kotlin.math.abs(it.box.top-wantedY)<0.008 &&
                it.box.left in (if(wantedColumn==0) 0.08..0.11 else 0.51..0.55) && it.text.any { ch -> ch.isDigit() } }
            val recovered=QuestionLayoutDetector.analyze(number-1,noMarker,layout)
            recovered.proposals.forEach { p ->
                val bounds=recovered.columnBounds[p.id.split("-")[1].toInt()]
                assertTrue(p.crop.left>=bounds.left && p.crop.right<=bounds.right)
            }
            assertTrue("Page $number raster-only marker recovery",recovered.anchors.any {
                it.source=="visual recovered" && it.column==wantedColumn && kotlin.math.abs(it.box.top-wantedY)<0.015
            })
        }
    }
}
