package com.xnotes.ui

import androidx.compose.runtime.*
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.*

/** Same popup contract as Editor, with command routing owned by Question Mode. */
class QuestionTools(configs: Map<Tool, ToolConfig>, colors: List<Rgba>, colorIndex: Int,
    override val hostRecentColors: List<Rgba>,
    private val selection: EditorToolState = EditorToolState(),
) : ToolPopupHost {
    private val configs = configs.toMutableMap()
    var surfaces: () -> List<AnswerCanvasController> = { emptyList() }
    private var annotationActive by mutableStateOf(false)
    val active get() = surfaces().firstOrNull { it.annotation == annotationActive } ?: surfaces().firstOrNull()
    private var projecting = false
    val selected get() = selection.questionTool
    val tool get() = selected
    val usingFallback get() = !EditorToolState.supported(selection.tool)
    override var hostToolbarColors by mutableStateOf(colors)
        private set
    override var hostActiveColorIndex by mutableIntStateOf(colorIndex)
        private set
    override var hostShapeConfig by mutableStateOf(ShapeConfig())
        private set
    val inkColor get() = hostToolbarColors[hostActiveColorIndex]
    fun activate(canvas: AnswerCanvasController) { annotationActive = canvas.annotation }
    fun select(tool: Tool) {
        if (!EditorToolState.supported(tool)) return
        selection.select(tool)
        project { surfaces().forEach { it.select(selected) } }
    }
    private fun project(block: () -> Unit) {
        val previous = projecting
        projecting = true
        try { block() } finally { projecting = previous }
    }
    fun pickColor(index: Int) { hostActiveColorIndex = index; surfaces().forEach { it.controller.pickInk(inkColor) } }
    fun configure(canvas: AnswerCanvasController) {
        canvas.toolChanged = { tool ->
            if (!projecting && EditorToolState.supported(tool)) {
                selection.select(tool)
                project { surfaces().filter { it !== canvas }.forEach { it.select(selected) } }
            }
        }
        canvas.gestureAction = ::gesture
        configs.forEach { (tool, config) -> canvas.controller.setToolConfig(tool, config) }
        canvas.controller.inkColor = inkColor
        canvas.controller.shapeConfig = hostShapeConfig
        project { canvas.select(selected) }
    }
    fun refresh() { surfaces().forEach(::configure) }
    fun gesture(action: String) {
        val canvas = active ?: return
        if (!canvas.inputEnabled) return
        when (action) {
            "undo" -> canvas.undo()
            "redo" -> canvas.redo()
            "toggle_eraser" -> select(if (tool == Tool.ERASER) Tool.PEN else Tool.ERASER)
            "toggle_pan" -> select(if (tool == Tool.PAN) Tool.PEN else Tool.PAN)
            "toggle_previous" -> canvas.controller.previousTool?.let(::select)
        }
    }
    override fun toolConfig(tool: Tool) = configs[tool] ?: ToolDefaults.configFor(tool)
    override fun updateToolConfig(tool: Tool, config: ToolConfig) {
        configs[tool] = config
        surfaces().forEach { it.controller.setToolConfig(tool, config.copy(rgba = inkColor)) }
    }
    override fun updateShapeConfig(config: ShapeConfig) { hostShapeConfig = config; surfaces().forEach { it.controller.shapeConfig = config } }
    override fun setSwatchColor(index: Int, color: Rgba) {
        hostToolbarColors = hostToolbarColors.toMutableList().also { it[index] = color }
        pickColor(index)
    }
    override fun rememberSwatchColor(index: Int) = Unit
}
