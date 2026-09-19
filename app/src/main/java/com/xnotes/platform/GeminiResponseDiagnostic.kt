package com.xnotes.platform

import com.xnotes.core.verification.*
import org.json.JSONObject

/** Debug-only, bounded response TEXT. Never retain raw requests, credentials or image data. */
internal object GeminiResponseDiagnostic {
    fun parse(body: String, debug: Boolean, apiKey: String, imageBase64: String): VerificationResult {
        val diagnostic = if (debug) sanitize(extractText(body), apiKey, imageBase64) else null
        try {
            return GeminiVerificationJson.response(body).copy(debugResponse = diagnostic)
        } catch (e: Exception) {
            val reason = sanitize(e.message ?: e.javaClass.simpleName, apiKey, imageBase64)
            throw VerificationFailure("Could not decode a complete semantic decision response",
                VerificationStage.PARSING, diagnostic?.plus("\nParser rejection: $reason"))
        }
    }
    private fun extractText(body: String): String = try {
        val candidates = JSONObject(body).getJSONArray("candidates")
        buildString {
            for (i in 0 until candidates.length()) {
                val candidate = candidates.getJSONObject(i)
                val finish = candidate.optString("finishReason").takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "unknown"
                appendLine("Candidate ${i + 1} · finishReason=$finish")
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                appendLine("Text content exists: ${parts != null && (0 until parts.length()).any { parts.getJSONObject(it).opt("text") is String }}")
                if (parts == null) appendLine("[No response text]") else for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    if (!part.optBoolean("thought", false) && part.opt("text") is String) appendLine(part.getString("text"))
                }
            }
        }.ifBlank { "[No candidates]" }
    } catch (_: Exception) { "[Malformed response envelope]\n$body" }

    fun sanitize(text: String, apiKey: String, imageBase64: String): String {
        var clean = text
        for (secret in listOf(apiKey, imageBase64).filter { it.isNotEmpty() }) {
            clean = clean.replace(secret, "[redacted]")
                .replace(java.net.URLEncoder.encode(secret, "UTF-8"), "[redacted]")
            // Also cover a secret echoed inside an escaped JSON string.
            clean = clean.replace(JSONObject.quote(secret).removeSurrounding("\""), "[redacted]")
        }
        // Request echoes are not diagnostics. Preserve ordinary operations JSON and wrong response fields.
        if (Regex("""(?i)(contents|inline_?data|generation_?config|system_?instruction)\\?"\s*:""").containsMatchIn(clean))
            return "[Request-shaped content redacted]"
        clean = clean.replace(Regex("""data:[^\s"]+""", RegexOption.IGNORE_CASE), "[image redacted]")
            .replace(Regex("AIza[A-Za-z0-9_-]+"), "[key redacted]")
            .replace(Regex("""(?i)(?:x-goog-api-key|api[_ -]?key)\\?"?\s*[:=]\s*\\?"?[^\s,;"]+"""), "[key redacted]")
            .replace(Regex("[A-Za-z0-9+/=_-]{64,}"), "[payload redacted]")
        clean = clean.map { if (it.isISOControl() && it != '\n' && it != '\t') ' ' else it }.joinToString("")
        return clean.take(12_000) + if (clean.length > 12_000) "\n[Response diagnostic truncated]" else ""
    }
}
