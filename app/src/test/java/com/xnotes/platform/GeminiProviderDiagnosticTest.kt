package com.xnotes.platform

import com.xnotes.BuildConfig
import com.xnotes.core.verification.*
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Explicit opt-in only. Uses a synthetic PNG, never workbook data. Prints sanitized status only. */
class GeminiProviderDiagnosticTest {
    @Test fun unchangedRequestDiagnostic() = runBlocking {
        if (System.getenv("XNOTES_GEMINI_DIAGNOSTIC") != "1" || !BuildConfig.DEBUG) return@runBlocking
        val model = System.getenv("XNOTES_GEMINI_DIAGNOSTIC_MODEL") ?: BuildConfig.GEMINI_MODEL
        val config = GeminiVerifierConfig(BuildConfig.GEMINI_API_KEY, model)
        check(config.available) { "Development Gemini configuration unavailable" }
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jBz0AAAAASUVORK5CYII=")
        try {
            GeminiQuestionCropVerifier(config).verify(VerifierPageInput(0,png,"image/png"),emptyList())
            println("Gemini diagnostic: request accepted")
        } catch (e: VerificationFailure) { println("Gemini diagnostic: " + e.status) }
    }
}
