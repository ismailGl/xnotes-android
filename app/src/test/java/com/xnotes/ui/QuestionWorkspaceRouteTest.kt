package com.xnotes.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionWorkspaceRouteTest {
    @Test fun rightSourceQuestionOpensInDifferentLeftNotebook() {
        val selected = SourceQuestion("folder/B.xnote", "set-b", "q1")
        assertEquals(QuestionWorkspaceAction.OPEN_SOURCE,
            QuestionWorkspaceRoute.action(selected, listOf("q1", "q2"), "folder/A.xnote", "set-a"))
    }

    @Test fun subsequentClickInSameSourceJumpsWithoutReload() {
        val selected = SourceQuestion("folder/B.xnote", "set-b", "q2")
        assertEquals(QuestionWorkspaceAction.JUMP,
            QuestionWorkspaceRoute.action(selected, listOf("q1", "q2"), "folder/B.xnote", "set-b"))
    }

    @Test fun sameQuestionIdInAnotherNotebookOrSetCannotJumpIntoWrongSession() {
        val selected = SourceQuestion("folder/B.xnote", "set-b", "q1")
        assertEquals(QuestionWorkspaceAction.OPEN_SOURCE,
            QuestionWorkspaceRoute.action(selected, listOf("q1"), "folder/A.xnote", "set-b"))
        assertEquals(QuestionWorkspaceAction.OPEN_SOURCE,
            QuestionWorkspaceRoute.action(selected, listOf("q1"), "folder/B.xnote", "set-a"))
    }

    @Test fun staleOutlineIdIsIgnored() {
        assertEquals(QuestionWorkspaceAction.IGNORE,
            QuestionWorkspaceRoute.action(SourceQuestion("folder/B.xnote", "set-b", "old"),
                listOf("q1"), "folder/A.xnote", "set-a"))
    }
}
