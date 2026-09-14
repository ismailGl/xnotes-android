package com.xnotes.core.model

/** Shared page-source contract; geometry is upright and normalized for either extractor. */
enum class QuestionTextSource(val label: String) { PDF_TEXT("PDF text"), OCR("OCR") }

object QuestionTextQuality {
    fun usable(runs: List<QuestionLayoutDetector.TextRun>): Boolean {
        val text = runs.joinToString("") { it.text }.filterNot { it.isWhitespace() }
        if (text.length < 40) return false // Empty layers and scan headers/page numbers.
        val bad = text.count { it == '\uFFFD' || it.isISOControl() || Character.getType(it) == Character.PRIVATE_USE.toInt() }
        if (bad.toDouble() / text.length > 0.05 || text.count { it.isLetterOrDigit() } < text.length * 0.45) return false
        val body = runs.filter { it.box.top >= 0.04 && it.box.bottom <= 0.95 }
        if (body.isEmpty()) return false
        // Reject tiny overlays and collapsed/mispositioned font mappings.
        val height = body.maxOf { it.box.bottom } - body.minOf { it.box.top }
        return height >= 0.08 && body.count { it.box.bottom - it.box.top in 0.002..0.08 } >= body.size * 0.8
    }
}
