package com.xnotes.ui

import com.xnotes.core.model.Document
import com.xnotes.core.model.NotebookQuestionPage
import com.xnotes.core.model.Question
import com.xnotes.platform.AnswerStore

/** The notebook owns persistence and History. The temporary one-page view owns neither. */
class NotebookQuestionStore(private val notebook: Document, private val questions: List<Question>,
    private val saveNotebook: suspend () -> Unit,
) : AnswerStore {
    var current: NotebookQuestionPage? = null
        private set
    override suspend fun load(answerId: String): Document {
        val question = questions.single { it.id == answerId }
        return NotebookQuestionPage(notebook, question).also { current = it }.viewDocument
    }
    override suspend fun save(answerId: String, document: Document) {
        // Never encode the one-page view. Editor snapshots and saves the complete notebook.
        saveNotebook()
    }
}
