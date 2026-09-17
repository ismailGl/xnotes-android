package com.xnotes.platform

import com.xnotes.core.verification.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiResponseDiagnosticTest {
    private fun envelope(text: String) = JSONObject().put("candidates", org.json.JSONArray().put(
        JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts",
            org.json.JSONArray().put(JSONObject().put("text", text)))))).toString()

    @Test fun parsingFailuresExposeOnlyDebugResponse() {
        for (text in listOf("Here are the crops", "{broken", "{\"wrong\":[]}")) {
            for (debug in listOf(true, false)) {
                try { GeminiResponseDiagnostic.parse(envelope(text), debug, "", ""); fail() }
                catch (e: VerificationFailure) {
                    assertEquals(VerificationStage.PARSING, e.stage)
                    if (debug) assertTrue(e.debugResponse!!.contains(text)) else assertNull(e.debugResponse)
                }
            }
        }
    }
    @Test fun validOperationsRetainDiagnosticForLaterLocalValidationOnlyInDebug() {
        val text = "{\"operations\":[{\"action\":\"KEEP\",\"id\":\"unknown\"}]}"
        val result = GeminiResponseDiagnostic.parse(envelope(text), true, "", "")
        assertTrue(result.debugResponse!!.contains(text))
        try { VerificationPatch.apply(0, emptyList(), result); fail() } catch (_: IllegalArgumentException) {}
        assertNull(GeminiResponseDiagnostic.parse(envelope(text), false, "", "").debugResponse)
    }
    @Test fun redactsSecretsPayloadsAndRequestEchoesButKeepsJson() {
        val clean = GeminiResponseDiagnostic.sanitize("secret-key AQID " + "A".repeat(100), "secret-key", "AQID")
        assertFalse(clean.contains("secret-key")); assertFalse(clean.contains("AQID")); assertFalse(clean.contains("A".repeat(64)))
        val request = "{\"contents\":[{\"text\":\"private prompt\"}]}"
        for (echo in listOf(request, JSONObject.quote(request)))
            assertEquals("[Request-shaped content redacted]", GeminiResponseDiagnostic.sanitize(echo, "", ""))
        val json = "{\"operations\":[]}"
        assertEquals(json, GeminiResponseDiagnostic.sanitize(json, "", ""))
        assertTrue(GeminiResponseDiagnostic.sanitize("words ".repeat(3000), "", "").length < 12100)
    }
    @Test fun queueSeparatesStagesAndGatesDiagnostics() {
        for (stage in VerificationStage.values()) for (debug in listOf(true, false)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var status = ""
            var diagnostic: String? = null
            var applied = false
            val verifier = QuestionCropVerifier { _, _ ->
                when (stage) {
                    VerificationStage.NETWORK -> throw java.io.IOException("unsafe exception text")
                    VerificationStage.PARSING -> throw VerificationFailure("decode failed", stage, "safe response")
                    else -> VerificationResult(listOf(VerificationOperation(VerificationAction.KEEP, "unknown")), "safe response")
                }
            }
            val queue = VerificationQueue(scope, "doc", "v", verifier,
                { if (stage == VerificationStage.PAGE_LOAD) error("load failed") else VerifierPageInput(it, byteArrayOf()) },
                { emptyList() }, { _, _ -> applied = true }, { _, s -> status = s },
                debugDiagnostics = debug, diagnostic = { _, s -> diagnostic = s })
            try {
                queue.review(0, listOf(0), true)
                assertTrue(status, status.contains(stage.label + " failure"))
                assertFalse(applied)
                assertFalse(status.contains("unsafe"))
                if (debug && stage in listOf(VerificationStage.PARSING, VerificationStage.VALIDATION))
                    assertEquals("safe response", diagnostic) else assertNull(diagnostic)
            } finally { queue.close(); scope.cancel() }
        }
    }
}
