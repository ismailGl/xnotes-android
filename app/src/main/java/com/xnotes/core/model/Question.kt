package com.xnotes.core.model

/** Fractions of the upright source PDF page, top-left origin, independent of view rotation/DPI. */
data class NormalizedRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 })
        require(right > left && bottom > top)
    }
}

data class Question(val id: String, val sourcePageIndex: Int, val crop: NormalizedRect) {
    init { require(id.isNotBlank() && sourcePageIndex >= 0) }
    /** Stable derived reference: old question JSON needs no migration or ink fields. */
    val answerDocumentId: String get() = id
}

/** Metadata only. The saved notebook owns the embedded PDF; its hash detects source replacement. */
data class QuestionSet(
    val id: String,
    val title: String,
    val questions: List<Question>,
    val sourceNotebookUri: String,
    val sourcePdfSha256: String,
)
