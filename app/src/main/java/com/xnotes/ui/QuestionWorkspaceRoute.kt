package com.xnotes.ui

/** A question is identified by its source notebook and set as well as its local question ID. */
data class SourceQuestion(val notebookUri: String, val setId: String, val questionId: String)

enum class QuestionWorkspaceAction { IGNORE, JUMP, OPEN_SOURCE }

object QuestionWorkspaceRoute {
    fun action(selection: SourceQuestion, sourceQuestionIds: Collection<String>,
        workspaceUri: String?, workspaceSetId: String?): QuestionWorkspaceAction = when {
        selection.questionId !in sourceQuestionIds -> QuestionWorkspaceAction.IGNORE
        selection.notebookUri == workspaceUri && selection.setId == workspaceSetId -> QuestionWorkspaceAction.JUMP
        else -> QuestionWorkspaceAction.OPEN_SOURCE
    }
}
