package com.xnotes.platform

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.QuestionLayoutDetector
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** One recognizer per sequential scan. No model download or remote recognition. */
class MlKitQuestionTextReader : Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(bitmap: Bitmap): List<QuestionLayoutDetector.TextRun> {
        currentCoroutineContext().ensureActive()
        // ML Kit cannot cancel native inference. Drain this one request before the caller
        // recycles its bitmap/closes the recognizer; cancellation never starts another page.
        val result = suspendCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnCompleteListener(Executor { it.run() }) { task ->
                    if (task.isSuccessful) continuation.resume(task.result)
                    else continuation.resumeWithException(task.exception ?: IllegalStateException("OCR failed"))
                }
        }
        currentCoroutineContext().ensureActive()
        // Elements preserve gaps/columns even if ML Kit puts both columns in one block.
        return result.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { element ->
            val b = element.boundingBox ?: return@mapNotNull null
            val l = (b.left.toDouble() / bitmap.width).coerceIn(0.0, 1.0)
            val t = (b.top.toDouble() / bitmap.height).coerceIn(0.0, 1.0)
            val r = (b.right.toDouble() / bitmap.width).coerceIn(0.0, 1.0)
            val bottom = (b.bottom.toDouble() / bitmap.height).coerceIn(0.0, 1.0)
            if (r <= l || bottom <= t || element.text.isBlank()) null
            else QuestionLayoutDetector.TextRun(element.text, NormalizedRect(l, t, r, bottom))
        }
    }
    override fun close() = recognizer.close()
}
