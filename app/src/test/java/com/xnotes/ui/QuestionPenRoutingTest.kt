package com.xnotes.ui

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionPenRoutingTest {
    @Test fun penStartsAndEndsEvenWhenAPalmWasAlreadyDown() {
        assertEquals(MotionEvent.ACTION_DOWN, QuestionInkViewport.penAction(MotionEvent.ACTION_POINTER_DOWN, 1, 1))
        assertEquals(MotionEvent.ACTION_UP, QuestionInkViewport.penAction(MotionEvent.ACTION_POINTER_UP, 1, 1))
    }
    @Test fun palmContactsCannotStartAPinchOrEndThePenStroke() {
        assertEquals(MotionEvent.ACTION_MOVE, QuestionInkViewport.penAction(MotionEvent.ACTION_POINTER_DOWN, 1, 0))
        assertEquals(MotionEvent.ACTION_MOVE, QuestionInkViewport.penAction(MotionEvent.ACTION_POINTER_UP, 1, 0))
        assertEquals(MotionEvent.ACTION_CANCEL, QuestionInkViewport.penAction(MotionEvent.ACTION_CANCEL, 0, 0))
    }
}
