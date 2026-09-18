package com.xnotes.platform

import com.xnotes.BuildConfig
import com.xnotes.core.verification.*
import com.xnotes.core.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Explicit opt-in; synthetic question page, sanitized output only. */
class GeminiProviderDiagnosticTest {
    @Test fun unchangedRequestDiagnostic() = runBlocking {
        if (System.getenv("XNOTES_GEMINI_DIAGNOSTIC") != "1" || !BuildConfig.DEBUG) return@runBlocking
        val config = GeminiVerifierConfig(BuildConfig.GEMINI_API_KEY, "gemini-3.5-flash-lite")
        check(config.available)
        val image = javaClass.getResourceAsStream("/gemini-diagnostic.png")!!.use { it.readBytes() }
        val page=VerifierPageInput(0,image,"image/png")
        val original=listOf(DetectedQuestion("p1",0,NormalizedRect(.07,.07,.85,.23)))
        val request=JSONObject(GeminiVerificationJson.request(page,original.map { VerifierProposal(it.id,it.crop) }))
        val schema=System.getenv("XNOTES_GEMINI_SCHEMA")
        if (schema != null) request.getJSONObject("generationConfig").getJSONObject("responseFormat")
            .getJSONObject("text").put("schema",JSONObject(schema))
        val connection=URL("https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent").openConnection() as HttpURLConnection
        try {
            connection.requestMethod="POST"; connection.doOutput=true
            connection.connectTimeout=15000; connection.readTimeout=55000
            connection.setRequestProperty("Content-Type","application/json"); config.authenticate(connection)
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            val code=connection.responseCode
            if(code !in 200..299) {
                val body=connection.errorStream?.bufferedReader()?.use { it.readText() }
                println(config.errorStatus(code,body,page.image)); return@runBlocking
            }
            println("HTTP $code")
            val body=connection.inputStream.bufferedReader().use { it.readText() }
            try {
                val result=config.parseResponse(body,page.image)
                println(result.debugResponse)
                try { VerificationPatch.apply(0,original,result); println("Local validation passed") }
                catch(e:Exception) { println("Validator rejection: " + e.message?.takeIf { it.startsWith("Local rule:") }) }
            } catch(e:VerificationFailure) { println(e.debugResponse); println("Stage: ${e.stage}") }
        } finally { connection.disconnect() }
    }
}
