package com.xnotes.platform

import org.json.JSONObject

/** Temporary debug-only provider diagnostics. Never return raw error/request bodies or details. */
internal object GeminiHttpError {
    const val MAX_BODY_BYTES = 65_536
    fun display(httpCode: Int, body: String?, debug: Boolean, apiKey: String, imageBase64: String): String {
        val generic = when (httpCode) {
            429 -> "AI rate limited · retry later"
            401, 403 -> "AI authentication failed · check development key"
            else -> "AI service failed (HTTP $httpCode)"
        }
        if (!debug) return generic
        if (body == null || body.length > MAX_BODY_BYTES) return "$generic · provider error unavailable"
        return try {
            val error = JSONObject(body).getJSONObject("error")
            val code = (error.opt("code") as? Number)?.toInt()?.takeIf { it in 100..599 } ?: httpCode
            val status = (error.opt("status") as? String ?: error.opt("type") as? String)
                ?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,79}")) } ?: "UNKNOWN"
            val message = error.opt("message") as? String ?: "No provider message"
            "AI HTTP $httpCode · code=$code · status=${sanitize(status, apiKey, imageBase64)} · " +
                sanitize(message, apiKey, imageBase64)
        } catch (_: Exception) { "$generic · provider error unavailable" }
    }
    private fun sanitize(value: String, apiKey: String, imageBase64: String): String {
        var clean = value
        // Redact known secrets before truncating (including short test payloads).
        for (secret in listOf(apiKey, imageBase64).filter { it.isNotEmpty() }) {
            clean = clean.replace(secret, "[redacted]")
            clean = clean.replace(java.net.URLEncoder.encode(secret, "UTF-8"), "[redacted]")
        }
        // Never expose an echoed JSON request/object or a data URI, even in the message field.
        clean = clean.substringBefore('{').let { if (it.length < clean.length) "$it[structured content redacted]" else it }
        clean = clean.replace(Regex("data:[^\\s]+", RegexOption.IGNORE_CASE), "[image redacted]")
        clean = clean.replace(Regex("AIza[A-Za-z0-9_-]+"), "[key redacted]")
        clean = clean.replace(Regex("(?i)(?:x-goog-api-key|api[_ -]?key|key)\\s*[:=]\\s*[^\\s,;]+"), "[key redacted]")
        clean = clean.replace(Regex("[A-Za-z0-9+/=_-]{64,}"), "[payload redacted]")
        clean = clean.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        return clean.take(1500).ifBlank { "[provider content redacted]" }
    }
}
