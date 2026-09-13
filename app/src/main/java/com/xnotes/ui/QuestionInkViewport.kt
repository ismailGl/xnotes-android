package com.xnotes.ui

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.ZoomPanTransform
import com.xnotes.core.tools.Tool

/** One viewport for PDF pixels, hit testing, dry ink and front-buffer ink. */
class QuestionInkViewport(
    private val canvas: AnswerCanvasController,
    private val current: () -> ZoomPanTransform,
    private val update: (ZoomPanTransform) -> Unit,
) {
    private var penStream = false
    private var lastPenTime = Long.MIN_VALUE
    private val scale = ScaleGestureDetector(canvas.view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                change(Pt(detector.focusX.toDouble(), detector.focusY.toDouble()), Pt(0.0, 0.0), detector.scaleFactor.toDouble())
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

    fun apply(transform: ZoomPanTransform) {
        canvas.state.applyCropViewport(transform)
        canvas.controller.frontInk?.surfaceLost()
        canvas.view.requestRender()
    }

    fun touch(event: MotionEvent): Boolean {
        val hasPen = (0 until event.pointerCount).any {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) penStream = hasPen
        if ((hasPen || penStream) && canvas.tool != Tool.PAN) {
            lastPenTime = event.eventTime
            val result = canvas.controller.onTouch(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) penStream = false
            return result
        }
        // Ignore trailing palm contacts after a pen stroke.
        if (!hasPen && lastPenTime != Long.MIN_VALUE && event.eventTime - lastPenTime < 250) return true
        scale.onTouchEvent(event)
        taps.onTouchEvent(event)
        return true
    }
}
