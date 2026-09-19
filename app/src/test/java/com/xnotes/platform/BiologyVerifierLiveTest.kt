package com.xnotes.platform

import com.xnotes.BuildConfig
import com.xnotes.core.model.*
import com.xnotes.core.verification.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

/** Explicit opt-in private corpus; never sends workbook content in ordinary tests. */
class BiologyVerifierLiveTest {
    @Test fun semanticVerificationOnRealPages() = runBlocking {
        val dir=(System.getenv("XNOTES_BIOLOGY_LIVE") ?: System.getenv("XNOTES_BIOLOGY_PLAN"))?.let(::File) ?: return@runBlocking
        check(BuildConfig.DEBUG)
        val verifier=GeminiQuestionCropVerifier(GeminiVerifierConfig(BuildConfig.GEMINI_API_KEY,"gemini-3.5-flash-lite"))
        for(n in 8..16) {
            val input=JSONObject(GZIPInputStream(File(dir,"page-$n.json.gz").inputStream()).bufferedReader().use { it.readText() })
            val runs=input.getJSONArray("runs").let { a -> (0 until a.length()).map { i ->
                val r=a.getJSONArray(i)
                QuestionLayoutDetector.TextRun(r.getString(0),NormalizedRect(r.getDouble(1),r.getDouble(2),r.getDouble(3),r.getDouble(4)))
            } }
            val ink=Base64.getDecoder().decode(input.getString("ink"))
            val layout=QuestionLayoutDetector.Layout(
                input.getInt("width"),input.getInt("height"),BooleanArray(ink.size) { ink[it].toInt()!=0 })
            val diagnostics=QuestionLayoutDetector.analyze(n-1,runs,layout)
            val detector=diagnostics.proposals
            val hints=detector.map { VerifierProposal(it.id,it.crop) }
            val plan=LocalVerificationPlan.build(hints,runs,layout,diagnostics)
            fun rect(b:NormalizedRect)=JSONArray(listOf(b.left,b.top,b.right,b.bottom))
            val metadata=JSONObject().put("proposals",JSONArray(hints.mapIndexed { i,p -> JSONObject().put("id","P${i+1}").put("box",rect(p.crop)) })).put("candidates",JSONArray(plan.candidates.map { c -> JSONObject()
                .put("id",c.id).put("action",c.action.name).put("inputs",JSONArray(c.proposals))
                .put("rectangles",JSONArray(c.rectangles.map(::rect))) }))
                .put("regions",JSONArray(diagnostics.regions.map { JSONObject().put("role",it.role.name).put("box",rect(it.box)).put("reason",it.reason) }))
            File(dir,"plan-$n.json").writeText(metadata.toString(2))
            if(System.getenv("XNOTES_BIOLOGY_LIVE")==null) continue
            fun boxes(p:List<DetectedQuestion>) = JSONArray(p.map { JSONArray(listOf(it.crop.left,it.crop.top,it.crop.right,it.crop.bottom)) })
            val report=JSONObject().put("page",n).put("detector",boxes(detector))
            try {
                val result=verifier.verify(VerifierPageInput(n-1,File(dir,"page-$n.jpg").readBytes(),plan=plan,renderRegion={ box ->
                    val pi=hints.indexOfFirst { it.crop==box }
                    val label=if(pi>=0) "P${pi+1}" else plan.candidates.first { it.action==SemanticAction.MISSED && it.rectangles.single()==box }.id
                    File(dir,"snippet-$n-$label.jpg").readBytes()
                }),hints)
                println("Page $n: ${result.debugResponse}")
                val final=VerificationPatch.apply(n-1,detector,result)
                check(final.none { it.accepted })
                report.put("ai",boxes(final)).put("diagnostic",result.debugResponse)
                println("Page $n: strict validation passed, all proposals unaccepted")
            } catch(e:VerificationFailure) {
                println("Page $n: ${e.status} ${e.debugResponse}")
                report.put("error",e.status).put("diagnostic",e.debugResponse)
            }
            File(dir,"semantic-result-$n.json").writeText(report.toString(2))
        }
    }
}
