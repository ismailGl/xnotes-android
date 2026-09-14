package com.xnotes.ui

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.ZoomPanTransform

/** One viewport for PDF pixels, hit testing, dry ink and front-buffer ink. */
class QuestionInkViewport(
    private val canvas: AnswerCanvasController,
    private val current: () -> ZoomPanTransform,
    private val update: (ZoomPanTransform) -> Unit,
) {
    private var suppressPalmStream = false
    private var rejectPenStream = false
    private var scaleFocus = Pt.ZERO
    private val penProperties = arrayOf(MotionEvent.PointerProperties())
    private val penCoords = arrayOf(MotionEvent.PointerCoords())
    private val scale = ScaleGestureDetector(canvas.view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaleFocus = Pt(detector.focusX.toDouble(), detector.focusY.toDouble())
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val focus = Pt(detector.focusX.toDouble(), detector.focusY.toDouble())
                publish(current().pinch(scaleFocus, focus, detector.scaleFactor.toDouble()))
                scaleFocus = focus
                return true
            }
        }).apply { isQuickScaleEnabled = false; isStylusScaleEnabled = false }
    private val taps = GestureDetector(canvas.view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { publish(current().reset()); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scale.isInProgress) change(Pt(e2.x.toDouble(), e2.y.toDouble()), Pt(-distanceX.toDouble(), -distanceY.toDouble()), 1.0)
            return true
        }
    })
    private fun change(center: Pt, pan: Pt, zoom: Double) = publish(current().gesture(center, pan, zoom))
    private fun publish(next: ZoomPanTransform) { apply(next); update(next) }
    fun pan(dx: Double, dy: Double) = change(Pt.ZERO, Pt(-dx, -dy), 1.0)

    fun apply(transform: ZoomPanTransform) {
        canvas.state.applyCropViewport(transform)
        canvas.controller.frontInk?.surfaceLost()
        canvas.view.requestRender()
    }

    fun touch(event: MotionEvent): Boolean {
        val penIndex = (0 until event.pointerCount).firstOrNull {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) suppressPalmStream = false
        if (penIndex != null) {
            // Project only the pen into the existing engine, preserving every pressure/tilt
            // sample. A palm must never start the engine's separate notebook pinch transform.
            val penEvent = projectPen(event, penIndex)
            try {
                if (penEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                    rejectPenStream = canvas.state.pageCrop != null && canvas.state.pageIndexAtContent(
                        canvas.state.viewportToContent(Pt(penEvent.x.toDouble(), penEvent.y.toDouble()))) == null
                    val cancel = MotionEvent.obtain(event)
                    try {
                        cancel.action = MotionEvent.ACTION_CANCEL
                        scale.onTouchEvent(cancel)
                        taps.onTouchEvent(cancel)
                    } finally { cancel.recycle() }
                }
                suppressPalmStream = true // ignore fingers left down after the pen lifts
                if (rejectPenStream) return true
                return canvas.controller.onTouch(penEvent)
            } finally { penEvent.recycle() }
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) canvas.finishInput()
        if (suppressPalmStream) return true
        scale.onTouchEvent(event)
        taps.onTouchEvent(event)
        return true
    }

    private fun projectPen(event: MotionEvent, index: Int): MotionEvent {
        val action = penAction(event.actionMasked, event.actionIndex, index)
        event.getPointerProperties(index, penProperties[0])
        val history = if (action == MotionEvent.ACTION_MOVE) event.historySize else 0
        if (history > 0) event.getHistoricalPointerCoords(index, 0, penCoords[0])
        else event.getPointerCoords(index, penCoords[0])
        val projected = MotionEvent.obtain(event.downTime,
            if (history > 0) event.getHistoricalEventTime(0) else event.eventTime,
            action, 1, penProperties, penCoords, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
        for (h in 1 until history) {
            event.getHistoricalPointerCoords(index, h, penCoords[0])
            projected.addBatch(event.getHistoricalEventTime(h), penCoords, event.metaState)
        }
        if (history > 0) {
            event.getPointerCoords(index, penCoords[0])
            projected.addBatch(event.eventTime, penCoords, event.metaState)
        }
        return projected
    }

    companion object {
        internal fun penAction(action: Int, changedPointer: Int, penPointer: Int): Int = when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> if (changedPointer == penPointer) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_POINTER_UP -> if (changedPointer == penPointer) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            else -> action
        }
    }
}
