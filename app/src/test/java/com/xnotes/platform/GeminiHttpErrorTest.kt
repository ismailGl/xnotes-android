package com.xnotes.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiHttpErrorTest {
    private fun body(message: String) = JSONObject().put("error", JSONObject().put("code",400)
        .put("status","INVALID_ARGUMENT").put("message",message)
        .put("details", "NEVER DISPLAY DETAILS")).toString()
    @Test fun preservesProviderMessageAndCodes() {
        val message = "Unknown name responseMimeType at generation_config: Cannot find field."
        val result=GeminiHttpError.display(400,body(message),true,"secret","image")
        assertTrue(result.contains("code=400")); assertTrue(result.contains("status=INVALID_ARGUMENT"))
        assertTrue(result.endsWith(message)); assertFalse(result.contains("NEVER DISPLAY"))
    }
    @Test fun releaseNeverExposesProviderBody() {
        assertEquals("AI service failed (HTTP 400)",GeminiHttpError.display(400,body("private message"),false,"", ""))
    }
    @Test fun secretsPayloadAndEchoedRequestAreRedacted() {
        val secret="test-secret-key"
        val payload="aGVsbG8="
        val message="Bad $secret image $payload " + "A".repeat(100) + " request {\"contents\":[\"PRIVATE REQUEST\"]}"
        val result=GeminiHttpError.display(400,body(message),true,secret,payload)
        for (s in listOf(secret,payload,"A".repeat(100),"PRIVATE REQUEST","contents")) assertFalse(result.contains(s))
        assertTrue(result.contains("redacted"))
    }
    @Test fun malformedOversizeAndMissingErrorNeverExposeRawBody() {
        for (s in listOf("private plaintext", "{}", "x".repeat(70_000))) {
            val result=GeminiHttpError.display(400,s,true,"","")
            assertTrue(result.endsWith("provider error unavailable"))
        }
    }
    @Test fun supportsTypeAndSanitizesStatusToo() {
        val error=JSONObject().put("error",JSONObject().put("type","BadRequest").put("message","Invalid input")).toString()
        assertTrue(GeminiHttpError.display(400,error,true,"","").contains("status=BadRequest"))
        assertFalse(GeminiHttpError.display(400,body("Invalid input"),true,"INVALID_ARGUMENT","").contains("INVALID_ARGUMENT"))
    }
}
