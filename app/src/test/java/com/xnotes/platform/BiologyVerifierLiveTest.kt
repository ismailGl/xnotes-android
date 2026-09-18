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
    @Test fun independentSegmentationOnRealPages() = runBlocking {
        val dir=System.getenv("XNOTES_BIOLOGY_LIVE")?.let(::File) ?: return@runBlocking
        check(BuildConfig.DEBUG)
        val verifier=GeminiQuestionCropVerifier(GeminiVerifierConfig(BuildConfig.GEMINI_API_KEY,"gemini-3.5-flash-lite"))
        for(n in listOf(8,10,12,14,15)) {
            val input=JSONObject(GZIPInputStream(File(dir,"page-$n.json.gz").inputStream()).bufferedReader().use { it.readText() })
            val runs=input.getJSONArray("runs").let { a -> (0 until a.length()).map { i ->
                val r=a.getJSONArray(i)
                QuestionLayoutDetector.TextRun(r.getString(0),NormalizedRect(r.getDouble(1),r.getDouble(2),r.getDouble(3),r.getDouble(4)))
            } }
            val ink=Base64.getDecoder().decode(input.getString("ink"))
            val detector=QuestionLayoutDetector.analyze(n-1,runs,QuestionLayoutDetector.Layout(
                input.getInt("width"),input.getInt("height"),BooleanArray(ink.size) { ink[it].toInt()!=0 })).proposals
            fun boxes(p:List<DetectedQuestion>) = JSONArray(p.map { JSONArray(listOf(it.crop.left,it.crop.top,it.crop.right,it.crop.bottom)) })
            val report=JSONObject().put("page",n).put("detector",boxes(detector))
            try {
                val result=verifier.verify(VerifierPageInput(n-1,File(dir,"page-$n.jpg").readBytes()),detector.map { VerifierProposal(it.id,it.crop) })
                println("Page $n: ${result.debugResponse}")
                val final=VerificationPatch.apply(n-1,detector,result)
                check(final.none { it.accepted })
                report.put("ai",boxes(final))
                println("Page $n: strict validation passed, all proposals unaccepted")
            } catch(e:VerificationFailure) {
                println("Page $n: ${e.status} ${e.debugResponse}")
                report.put("error",e.status).put("diagnostic",e.debugResponse)
            }
            File(dir,"result-$n.json").writeText(report.toString(2))
        }
    }
}
