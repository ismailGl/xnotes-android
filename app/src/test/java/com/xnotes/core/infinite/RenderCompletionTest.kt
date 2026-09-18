package com.xnotes.core.infinite

import org.junit.Assert.*
import org.junit.Test

class RenderCompletionTest {
    @Test fun questionBackgroundDecodeSchedulesVisibleFrameWithoutPanOrZoom() {
        var backgroundReady = false
        var visible = false
        var frames = 0
        // Opening publishes an initial frame, which queues asynchronous image decoding.
        val decode = Runnable { backgroundReady = true }
        assertFalse(visible)
        RenderCompletion.run(decode) { frames++; visible = backgroundReady }
        assertTrue(visible)
        assertEquals(1,frames)
    }
}
