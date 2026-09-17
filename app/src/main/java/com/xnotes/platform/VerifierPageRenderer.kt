package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import com.xnotes.core.verification.VerifierPageInput
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** On-demand whole-page rendering, one bitmap at a time; no PDF is transmitted. */
class VerifierPageRenderer(private val context: Context, private val file: File) {
    suspend fun page(index: Int): VerifierPageInput = withContext(Dispatchers.IO) {
        val source = requireNotNull(PdfSource.create(context, file))
        try {
            val (width, height) = requireNotNull(source.pageSizePoints(index))
            val scale = 2048.0 / maxOf(width, height)
            ensureActive()
            val bitmap = requireNotNull(source.renderPage(index,
                (width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))).bitmap
            try {
                val bytes = ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)); it.toByteArray()
                }
                ensureActive()
                VerifierPageInput(index, bytes)
            } finally { bitmap.recycle() }
        } finally { source.close() }
    }
}
