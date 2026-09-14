package com.xnotes.ui

import androidx.compose.runtime.*
import com.xnotes.core.tools.Tool

/** Canonical tool + capture purpose, shared by the notebook and Question Mode. */
class EditorToolState(initial: Tool = Tool.DEFAULT) {
    data class Selection(val tool: Tool, val addQuestion: Boolean = false)
    var selection by mutableStateOf(Selection(initial))
        private set
    private var lastDrawingTool by mutableStateOf(if (supported(initial)) initial else Tool.PEN)
    private var suppressEngineChanges = 0
    val tool get() = selection.tool
    val addQuestion get() = selection.addQuestion
    val questionTool get() = if (supported(tool)) tool else lastDrawingTool

    fun select(tool: Tool) {
        selection = Selection(tool)
        if (supported(tool)) lastDrawingTool = tool
    }
    fun selectQuestion() { selection = Selection(Tool.SCREENSHOT, addQuestion = true) }
    fun clearQuestionPurpose() { selection = selection.copy(addQuestion = false) }
    fun engineChanged(tool: Tool) {
        // Cleanup and repeated engine notifications aren't explicit Screenshot choices.
        if (suppressEngineChanges == 0 && tool != this.tool) select(tool)
    }
    fun preserveDuringCleanup(block: () -> Unit) {
        suppressEngineChanges++
        try { block() } finally { suppressEngineChanges-- }
    }
    companion object {
        fun supported(tool: Tool) = tool.isStroke || tool in setOf(Tool.ERASER, Tool.PAN, Tool.SELECT, Tool.LASSO, Tool.SHAPE)
    }
}
