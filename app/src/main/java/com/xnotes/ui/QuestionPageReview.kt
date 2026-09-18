package com.xnotes.ui

import com.xnotes.core.model.Question
import com.xnotes.core.model.Rgba
import com.xnotes.platform.QuestionProgress
import com.xnotes.platform.QuestionResult

/** Original-page review input. No answer-key producer exists yet, so unanswered results stay unknown. */
data class QuestionPageReview(val sourcePageIndex: Int, val overlays: List<QuestionResultOverlay>) {
    companion object {
        fun create(page: Int, questions: List<Question>, progress: QuestionProgress) = QuestionPageReview(page,
            questions.filter { it.sourcePageIndex == page }.map {
                QuestionResultOverlay(it, progress.results[it.id] ?: QuestionResult.UNKNOWN)
            })
    }
}

data class QuestionResultOverlay(val question: Question, val result: QuestionResult) {
    val color: Rgba? get() = when (result) {
        QuestionResult.UNKNOWN -> null
        QuestionResult.CORRECT -> Rgba(22, 129, 60)
        QuestionResult.INCORRECT -> Rgba(206, 40, 40)
    }
    val indicator: String? get() = when (result) {
        QuestionResult.UNKNOWN -> null
        QuestionResult.CORRECT -> "✓"
        QuestionResult.INCORRECT -> "✕"
    }
}
