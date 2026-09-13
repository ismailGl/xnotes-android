package com.xnotes.ui

import androidx.compose.runtime.*
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.*

/** Same popup contract as Editor, with command routing owned by Question Mode. */
class QuestionTools(configs: Map<Tool, ToolConfig>, colors: List<Rgba>, colorIndex: Int,
    override val hostRecentColors: List<Rgba>,
) : ToolPopupHost {
    private val configs = configs.toMutableMap()
    var surfaces: () -> List<AnswerCanvasController> = { emptyList() }
    private var touched by mutableStateOf<AnswerCanvasController?>(null)
    val active get() = touched?.takeIf { it in surfaces() } ?: surfaces().firstOrNull()
    var selected by mutableStateOf(Tool.PEN)
        private set
    val tool get() = active?.tool ?: selected
    override var hostToolbarColors by mutableStateOf(colors)
        private set
    override var hostActiveColorIndex by mutableIntStateOf(colorIndex)
        private set
    override var hostShapeConfig by mutableStateOf(ShapeConfig())
        private set
    val inkColor get() = hostToolbarColors[hostActiveColorIndex]
    fun activate(canvas: AnswerCanvasController) { touched = canvas }
    fun select(tool: Tool) { selected = tool; surfaces().forEach { it.select(tool) } }
    fun pickColor(index: Int) { hostActiveColorIndex = index; surfaces().forEach { it.controller.pickInk(inkColor) } }
    fun configure(canvas: AnswerCanvasController) {
        configs.forEach { (tool, config) -> canvas.controller.setToolConfig(tool, config) }
        canvas.controller.inkColor = inkColor
        canvas.controller.shapeConfig = hostShapeConfig
        canvas.select(selected)
    }
    fun refresh() { surfaces().forEach(::configure) }
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
