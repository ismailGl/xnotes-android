package com.xnotes.core.infinite

/** A demand-rendered canvas must wake after asynchronous content becomes available. */
object RenderCompletion {
    fun run(work: Runnable, requestFrame: () -> Unit) {
        try { work.run() } finally { requestFrame() }
    }
}
