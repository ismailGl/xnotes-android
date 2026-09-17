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

/** Optional private second-book corpus; input files are ignored build artifacts. */
class QuestionRegionReplayTest {
    @Test fun secondBookRegionReplay() {
        val dir=File("build/region-replay-second")
        assumeTrue("Second-book exports unavailable",dir.isDirectory)
        val failures=mutableListOf<String>()
        for(number in listOf(6,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,25,27)) {
            val file=File(dir,"page-$number.json.gz")
            assumeTrue("Missing second-book page $number",file.exists())
            val json=JSONObject(GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() })
            val runs=json.getJSONArray("runs").let { a -> (0 until a.length()).map { i ->
                val r=a.getJSONArray(i)
                QuestionLayoutDetector.TextRun(r.getString(0),NormalizedRect(r.getDouble(1),r.getDouble(2),r.getDouble(3),r.getDouble(4)))
            } }
            val bytes=Base64.getDecoder().decode(json.getString("ink"))
            val d=QuestionLayoutDetector.analyze(number-1,runs,QuestionLayoutDetector.Layout(
                json.getInt("width"),json.getInt("height"),BooleanArray(bytes.size) { bytes[it].toInt()!=0 }))
            fun rect(r:NormalizedRect)=JSONArray(listOf(r.left,r.top,r.right,r.bottom))
            val result=JSONObject().put("page",number).put("columns",d.columnCount)
                .put("regions",JSONArray().apply { d.regions.forEach { r -> put(JSONObject().put("role",r.role.name).put("box",rect(r.box)).put("reason",r.reason)) } })
                .put("anchors",JSONArray().apply { d.anchors.forEach { a -> put(JSONObject().put("text",a.text).put("box",rect(a.box)).put("column",a.column).put("source",a.source).put("reason",a.reason)) } })
                .put("proposals",JSONArray().apply { d.proposals.forEach { p -> put(JSONObject().put("id",p.id).put("box",rect(p.crop))) } })
            File(dir,"result-$number.json").writeText(result.toString(2))
            try {
            if(number==6) {
                assertTrue("Contents must not produce questions",d.proposals.isEmpty())
                assertTrue(d.regions.any { it.role==QuestionPageRegions.Role.DOCUMENT })
            } else {
                val panel=d.regions.single { it.role==QuestionPageRegions.Role.INSTRUCTIONAL }
                val leftPanel=number in listOf(8,10,12,14,16,18,20,22)
                assertEquals("Sidebar side on $number",leftPanel,panel.box.left==0.0)
                assertEquals("Question area columns on $number",if(number in listOf(18,19)) 1 else 2,d.columnCount)
                assertTrue("Populated page $number",d.proposals.isNotEmpty())
            }
            if(number in listOf(9,11,13,15,17,19,21))
                assertTrue("Answer strip on $number",d.regions.any { it.role==QuestionPageRegions.Role.ANSWER_KEY })
            val completeCounts=mapOf(8 to 5,9 to 5,12 to 6,13 to 5,14 to 6,16 to 5,18 to 2,19 to 2,21 to 5)
            completeCounts[number]?.let { assertEquals("Complete starts on $number",it,d.proposals.size) }
            // Manually checked content extents on the actual raster corpus: number through
            // final options, including intervening graphics. Counts alone hide clipped crops.
            val coverage = mapOf(
                10 to listOf(listOf(.66,.11,.93,.29), listOf(.66,.53,.94,.91)),
                11 to listOf(listOf(.05,.08,.34,.54)),
                17 to listOf(listOf(.05,.69,.34,.90)),
                18 to listOf(listOf(.33,.12,.94,.54)),
                20 to listOf(listOf(.66,.12,.94,.28), listOf(.66,.49,.94,.91)),
                21 to listOf(listOf(.38,.08,.66,.37), listOf(.38,.57,.66,.90)),
                22 to listOf(listOf(.66,.12,.94,.35), listOf(.66,.48,.94,.92)),
                23 to listOf(listOf(.38,.08,.66,.37), listOf(.05,.31,.34,.60)),
                25 to listOf(listOf(.05,.36,.34,.59), listOf(.38,.45,.66,.69)),
                27 to listOf(listOf(.39,.08,.66,.26), listOf(.39,.40,.66,.58))
            )
            coverage[number]?.forEach { (left, top, right, bottom) ->
                assertEquals("Page $number must retain complete content $left,$top,$right,$bottom",
                    1, d.proposals.count { p -> p.crop.let { b ->
                        b.left<=left && b.top<=top && b.right>=right && b.bottom>=bottom
                    } })
                assertFalse("Page $number interior anchor fragments content",d.proposals.any { p ->
                    p.crop.left<right && p.crop.right>left && p.crop.top>top && p.crop.top<bottom
                })
            }
            if(number==18) assertTrue("Include image-first question",d.proposals.first().crop.top<0.11)
            if(number==19) assertTrue("Do not cut at numbered diagram exits",d.proposals.last().crop.bottom>0.92)
            if(number==22) assertTrue("Previously empty page",d.proposals.size>=3)
            d.proposals.forEach { p ->
                assertFalse("Page $number non-question overlap",d.regions.any { r ->
                    r.role!=QuestionPageRegions.Role.QUESTIONS && p.crop.left<r.box.right && p.crop.right>r.box.left &&
                        p.crop.top<r.box.bottom && p.crop.bottom>r.box.top
                })
                val bounds=d.columnBounds[p.id.split("-")[1].toInt()]
                assertTrue("Page $number clamp",p.crop.left>=bounds.left && p.crop.right<=bounds.right)
            }
            } catch(error: AssertionError) { failures += "Page $number: ${error.message}" }
        }
        assertTrue(failures.joinToString("; "),failures.isEmpty())
    }
}
