package com.xnotes.ui

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.CanvasView
import com.xnotes.canvas.FrontInk
import com.xnotes.canvas.InteractionController
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.snapshot
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.gl.GlWetPad
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.settings.Preferences
import com.xnotes.ui.theme.Palette

/** Paged xnotes ink host with no notebook paths, autosave bindings, or recovery directories. */
class AnswerCanvasController(
    context: Context,
    override val document: Document,
    palette: Palette,
    private val preferences: Preferences,
    configs: Map<Tool, ToolConfig>,
    color: Rgba,
    private val onChanged: () -> Unit,
    override val history: History = History(),
    val annotation: Boolean = false,
    private val onActive: (AnswerCanvasController) -> Unit = {},
) : AnswerSurface {
    val state = CanvasState(document, AndroidSurfaceFactory(), palette)
    val view = CanvasView(context).also { it.state = state }
    private val pad = GlWetPad(context, onTop = true)
    val surfaces = FrameLayout(context).apply {
        addView(view, FrameLayout.LayoutParams(-1, -1))
        addView(pad, FrameLayout.LayoutParams(-1, -1))
    }
    var tool by mutableStateOf(Tool.PEN)
        private set
    var canUndo by mutableStateOf(history.canUndo)
        private set
    var canRedo by mutableStateOf(history.canRedo)
        private set
    override var inputEnabled = true
    private var disposed = false
    var viewportTouch: ((android.view.MotionEvent) -> Boolean)? = null
    val controller = InteractionController(state, history, AndroidTextMeasurer(),
        requestRender = { render() }, onContentChanged = { changed() },
        onToolChanged = { tool = it })

    init {
        configs.forEach { (tool, config) -> controller.setToolConfig(tool, config) }
        controller.inkColor = color
        controller.fingerDraws = preferences.fingerDraws
        controller.zoomLockPan = preferences.zoomLockPan
        controller.detectShapes = preferences.detectShapes
        controller.penButtonTool = if (preferences.penButtonTool == "none") null else Tool.fromId(preferences.penButtonTool) ?: Tool.ERASER
        controller.penButtonHover = preferences.penButtonHover
        state.maxCachePx = preferences.maxCacheResolution.toDouble()
        pad.frontBuffering = !preferences.disableFrontBuffering
        controller.setTool(Tool.PEN)
        view.input = {
            if (inputEnabled && !disposed) {
                if (it.actionMasked == android.view.MotionEvent.ACTION_DOWN) onActive(this)
                viewportTouch?.invoke(it) ?: controller.onTouch(it)
            } else true
        }
        view.hover = { if (inputEnabled && !disposed) controller.onHover(it) else true }
        view.genericMotion = { if (inputEnabled && !disposed) controller.onGenericMotion(it) }
        view.drawOverlay = { r, _ -> controller.drawOverlay(r) }
        controller.frontInk = FrontInk(state, view, pad)
        pad.onSurfaceLost = { controller.frontInk?.surfaceLost() }
        view.onTwoFingerTap = { gesture(preferences.twoFingerTap) }
        view.onThreeFingerTap = { gesture(preferences.threeFingerTap) }
        view.onKey = { handleKey(it) }
        if (annotation) {
            view.transparentPaper = true
            state.pageBorders = false
            state.didInitialFit = true
            state.sideMargin = 0.0
            // Finger gestures are owned by the crop viewport, never by the ink controller.
            controller.fingerDraws = false
            state.relayout()
        }
    }

    private fun render() { if (!disposed && controller.frontInk?.live != true) view.requestRender() }
    private fun changed() {
        canUndo = history.canUndo
        canRedo = history.canRedo
        document.dirty = true
        onChanged()
    }

    fun select(tool: Tool) { if (inputEnabled) { finishInput(); controller.setTool(tool) } }
    fun undo() { if (inputEnabled && history.canUndo) { finishInput(); history.undo(); afterHistory() } }
    fun redo() { if (inputEnabled && history.canRedo) { finishInput(); history.redo(); afterHistory() } }
    private fun afterHistory() {
        controller.frontInk?.surfaceLost()
        state.refreshAllInk()
        changed()
        view.requestRender()
    }

    fun gesture(action: String) {
        if (!inputEnabled) return
        when (action) {
            "undo" -> undo()
            "redo" -> redo()
            "toggle_eraser" -> select(if (tool == Tool.ERASER) Tool.PEN else Tool.ERASER)
            "toggle_pan" -> select(if (tool == Tool.PAN) Tool.PEN else Tool.PAN)
            "toggle_previous" -> controller.previousTool?.let { select(it) }
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!inputEnabled || event.action != KeyEvent.ACTION_DOWN) return false
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Z) {
            if (event.isShiftPressed) redo() else undo()
            return true
        }
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Y) { redo(); return true }
        return false
    }

    fun stylusButton(event: KeyEvent): Boolean = inputEnabled && controller.onStylusButtonKey(
        event.keyCode, event.action == KeyEvent.ACTION_DOWN)

    override fun finishInput() { if (!disposed) controller.cancelForTransition() }
    override fun snapshot(): Document = document.snapshot()
    override fun dispose() {
        if (disposed) return
        finishInput()
        controller.frontInk?.surfaceLost()
        pad.release()
        state.invalidateAllCaches()
        state.clearSharpViewport()
        state.releaseGeometryExcept(emptySet())
        inputEnabled = false
        disposed = true
        view.input = { true }
        view.hover = { true }
        view.genericMotion = null
        view.onKey = null
    }
}
