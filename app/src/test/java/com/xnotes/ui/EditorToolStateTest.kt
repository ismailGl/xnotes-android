package com.xnotes.ui

import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import org.junit.Assert.*
import org.junit.Test

class EditorToolStateTest {
    private fun tools(state: EditorToolState) = QuestionTools(emptyMap(), listOf(Rgba(0, 0, 0)), 0, emptyList(), state)

    @Test fun addQuestionSurvivesFallbackAndCleanupWithoutBecomingScreenshot() {
        val state = EditorToolState(Tool.ERASER)
        state.selectQuestion()
        val before = state.selection
        val question = tools(state)
        assertEquals(Tool.ERASER, question.tool)
        assertTrue(question.usingFallback)
        state.preserveDuringCleanup {
            state.engineChanged(Tool.SELECT)
            state.engineChanged(Tool.SCREENSHOT)
        }
        state.engineChanged(Tool.SCREENSHOT)
        assertEquals(before, state.selection)
        assertTrue(state.addQuestion)
    }

    @Test fun ordinaryScreenshotRemainsDistinctAndExplicitScreenshotClearsQuestionPurpose() {
        val state = EditorToolState()
        state.select(Tool.SCREENSHOT)
        tools(state) // opening without choosing a drawing tool does not mutate the canonical state
        assertEquals(Tool.SCREENSHOT, state.tool)
        assertFalse(state.addQuestion)
        state.selectQuestion()
        state.select(Tool.SCREENSHOT)
        assertFalse(state.addQuestion)
        assertEquals(Tool.SCREENSHOT, state.tool)
    }

    @Test fun sharedToolsCarryInBothDirections() {
        val state = EditorToolState(Tool.ERASER)
        val question = tools(state)
        assertEquals(Tool.ERASER, question.tool)
        question.select(Tool.PEN)
        assertEquals(Tool.PEN, state.tool)
        assertEquals(Tool.PEN, tools(state).tool)
        state.select(Tool.HIGHLIGHTER)
        assertEquals(Tool.HIGHLIGHTER, question.tool)
    }

    @Test fun explicitlySelectingFallbackPenOverridesAddQuestion() {
        val state = EditorToolState(Tool.PEN)
        state.selectQuestion()
        val question = tools(state)
        assertTrue(question.usingFallback)
        question.select(Tool.PEN)
        assertFalse(question.usingFallback)
        assertFalse(state.addQuestion)
        assertEquals(Tool.PEN, state.tool)
    }

    @Test fun unsupportedToolsAreRestoredAndRealEngineChangesRemainEffective() {
        val state = EditorToolState(Tool.TEXT)
        val before = state.selection
        assertEquals(Tool.PEN, tools(state).tool)
        assertEquals(before, state.selection)
        state.selectQuestion()
        state.engineChanged(Tool.ERASER)
        assertEquals(Tool.ERASER, state.tool)
        assertFalse(state.addQuestion)
    }
}
